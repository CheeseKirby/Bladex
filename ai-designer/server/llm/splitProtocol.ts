import { hashPlanContract, upsertPlanContractBlock, type PlanContract } from './planContract';

export interface ValidatedSubPlan {
  id: string;
  masterPlanId: string;
  index: number;
  title: string;
  planContent: string;
  prerequisites: string[];
  deliverableIds: string[];
  contractHash: string;
  referencedElementIds: string[];
  inputTypes: string[];
  outputTypes: string[];
  status: 'GENERATED';
}

export type SplitParseResult =
  | { ok: true; value: { subPlans: ValidatedSubPlan[] } }
  | { ok: false; error: string };

export interface SplitRecoveryResult {
  parsed: SplitParseResult;
  schemaRecovered: boolean;
}

export async function parseSplitModelResponseWithRecovery(
  rawResponse: string,
  contract: PlanContract,
  recover: (protocolError: string, malformedResponse: string) => Promise<string>,
  masterPlanId = 'plan_1',
): Promise<SplitRecoveryResult> {
  const primary = parseSplitModelResponse(rawResponse, contract, masterPlanId);
  if (primary.ok) return { parsed: primary, schemaRecovered: false };
  const recoveredResponse = await recover(primary.error, rawResponse);
  return { parsed: parseSplitModelResponse(recoveredResponse, contract, masterPlanId), schemaRecovered: true };
}

export function parseSplitModelResponse(rawResponse: string, contract: PlanContract, masterPlanId = 'plan_1'): SplitParseResult {
  const jsonText = extractJsonObject(rawResponse);
  if (!jsonText) return { ok: false, error: 'Split response is empty or does not contain a JSON object' };
  let parsed: unknown;
  try {
    parsed = JSON.parse(jsonText);
  } catch (error) {
    return { ok: false, error: `Split response JSON parse failed: ${errorMessage(error)}` };
  }
  if (!isRecord(parsed) || !Array.isArray(parsed.subPlans) || parsed.subPlans.length === 0) {
    return { ok: false, error: 'Split response.subPlans must be a non-empty array' };
  }
  if (parsed.subPlans.length > 12) return { ok: false, error: 'Split response contains more than 12 sub-plans' };

  const contractHash = hashPlanContract(contract);
  const defaultReferences = [
    ...contract.entities.map((item) => item.id),
    ...contract.fields.map((item) => item.id),
    ...contract.states.map((item) => item.id),
    ...contract.integrations.map((item) => item.id),
  ];
  const subPlans: ValidatedSubPlan[] = [];
  for (let index = 0; index < parsed.subPlans.length; index += 1) {
    const raw = parsed.subPlans[index];
    if (!isRecord(raw)) return { ok: false, error: `subPlans[${index}] must be an object` };
    if (!nonBlankString(raw.id)) return { ok: false, error: `subPlans[${index}].id must be a non-empty string` };
    if (!Number.isInteger(raw.index) || Number(raw.index) < 1) {
      return { ok: false, error: `subPlans[${index}].index must be a positive integer` };
    }
    if (!nonBlankString(raw.title)) return { ok: false, error: `subPlans[${index}].title must be a non-empty string` };
    if (!nonBlankString(raw.planContent)) return { ok: false, error: `subPlans[${index}].planContent must be a non-empty string` };
    if (!isStringArray(raw.prerequisites)) {
      return { ok: false, error: `subPlans[${index}].prerequisites must be a string array` };
    }
    if (!isStringArray(raw.deliverableIds) || raw.deliverableIds.length === 0) {
      return { ok: false, error: `subPlans[${index}].deliverableIds must be a non-empty string array` };
    }
    if (new Set(raw.deliverableIds).size !== raw.deliverableIds.length) {
      return { ok: false, error: `subPlans[${index}].deliverableIds must not contain duplicates` };
    }
    if (raw.referencedElementIds != null && !isStringArray(raw.referencedElementIds)) {
      return { ok: false, error: `subPlans[${index}].referencedElementIds must be a string array` };
    }
    const deliverableIds = Array.from(new Set(raw.deliverableIds));
    const deliverables = deliverableIds.map((id) => contract.deliverables.find((item) => item.id === id));
    if (deliverables.some((item) => !item)) {
      return { ok: false, error: `subPlans[${index}] references an unknown deliverable id` };
    }
    if (deliverables.some((item) => item?.action === 'PROHIBIT')) {
      return { ok: false, error: `subPlans[${index}] assigns a prohibited deliverable` };
    }
    const outputTypes = Array.from(new Set(deliverables.flatMap((item) => item?.providesTypes ?? [])));
    const inputTypes = Array.from(new Set(deliverables.flatMap((item) => item?.requiresTypes ?? [])
      .filter((type) => !outputTypes.includes(type))));
    subPlans.push({
      id: raw.id.trim(),
      masterPlanId,
      index: Number(raw.index),
      title: raw.title.trim(),
      planContent: upsertPlanContractBlock(raw.planContent.trim(), contract),
      prerequisites: raw.prerequisites,
      deliverableIds,
      contractHash,
      referencedElementIds: raw.referencedElementIds ?? defaultReferences,
      inputTypes,
      outputTypes,
      status: 'GENERATED',
    });
  }

  const ids = subPlans.map((subPlan) => subPlan.id);
  const indexes = subPlans.map((subPlan) => subPlan.index);
  if (new Set(ids).size !== ids.length) return { ok: false, error: 'Sub-plan ids must be unique' };
  if (new Set(indexes).size !== indexes.length) return { ok: false, error: 'Sub-plan indexes must be unique' };
  const expectedIndexes = Array.from({ length: subPlans.length }, (_, index) => index + 1);
  if (indexes.slice().sort((left, right) => left - right).some((value, index) => value !== expectedIndexes[index])) {
    return { ok: false, error: 'Sub-plan indexes must form a contiguous 1..N sequence' };
  }

  const idSet = new Set(ids);
  for (const subPlan of subPlans) {
    if (subPlan.prerequisites.includes(subPlan.id)) {
      return { ok: false, error: `Sub-plan ${subPlan.id} cannot depend on itself` };
    }
    for (const prerequisite of subPlan.prerequisites) {
      if (!idSet.has(prerequisite)) {
        return { ok: false, error: `Sub-plan ${subPlan.id} references unknown prerequisite ${prerequisite}` };
      }
    }
  }
  if (hasDependencyCycle(subPlans)) return { ok: false, error: 'Sub-plan prerequisites contain a cycle' };

  const knownElementIds = new Set([
    ...contract.domains, ...contract.modules, ...contract.aggregates, ...contract.entities,
    ...contract.fields, ...contract.states, ...contract.integrations, ...contract.deliverables,
    ...contract.referenceBindings, ...contract.architectureDecisions,
  ].map((item) => item.id));
  const owners = new Map<string, string>();
  for (const subPlan of subPlans) {
    for (const deliverableId of subPlan.deliverableIds) {
      const owner = owners.get(deliverableId);
      if (owner && owner !== subPlan.id) {
        return { ok: false, error: `Deliverable ${deliverableId} is assigned to multiple sub-plans` };
      }
      owners.set(deliverableId, subPlan.id);
    }
    const unknownReference = subPlan.referencedElementIds.find((id) => !knownElementIds.has(id));
    if (unknownReference) {
      return { ok: false, error: `Sub-plan ${subPlan.id} references unknown contract element ${unknownReference}` };
    }
  }
  const requiredDeliverables = contract.deliverables
    .filter((deliverable) => deliverable.kind !== 'OTHER' && deliverable.action !== 'PROHIBIT')
    .map((deliverable) => deliverable.id);
  const missing = requiredDeliverables.filter((id) => !owners.has(id));
  if (missing.length > 0) {
    return { ok: false, error: `Split response does not cover contract deliverables: ${missing.join(', ')}` };
  }

  const typeProviders = new Map<string, string>();
  for (const deliverable of contract.deliverables.filter((item) => item.action !== 'PROHIBIT')) {
    const owner = owners.get(deliverable.id);
    if (!owner) continue;
    for (const type of deliverable.providesTypes ?? []) typeProviders.set(type, owner);
  }
  const byId = new Map(subPlans.map((subPlan) => [subPlan.id, subPlan]));
  for (const subPlan of subPlans) {
    for (const type of subPlan.inputTypes) {
      const provider = typeProviders.get(type);
      if (provider && provider !== subPlan.id && !dependsTransitively(subPlan.id, provider, byId)) {
        return { ok: false, error: `Sub-plan ${subPlan.id} consumes ${type} from ${provider} without a prerequisite path` };
      }
    }
  }

  return { ok: true, value: { subPlans: subPlans.sort((left, right) => left.index - right.index) } };
}

function dependsTransitively(consumerId: string, providerId: string, byId: Map<string, ValidatedSubPlan>): boolean {
  const visited = new Set<string>();
  const visit = (id: string): boolean => {
    if (id === providerId) return true;
    if (!visited.add(id)) return false;
    return (byId.get(id)?.prerequisites ?? []).some(visit);
  };
  return visit(consumerId);
}

function hasDependencyCycle(subPlans: ValidatedSubPlan[]): boolean {
  const dependencies = new Map(subPlans.map((subPlan) => [subPlan.id, subPlan.prerequisites]));
  const visiting = new Set<string>();
  const visited = new Set<string>();
  const visit = (id: string): boolean => {
    if (visiting.has(id)) return true;
    if (visited.has(id)) return false;
    visiting.add(id);
    for (const dependency of dependencies.get(id) ?? []) if (visit(dependency)) return true;
    visiting.delete(id);
    visited.add(id);
    return false;
  };
  return subPlans.some((subPlan) => visit(subPlan.id));
}

function extractJsonObject(raw: string): string | null {
  let body = typeof raw === 'string' ? raw.trim() : '';
  if (!body) return null;
  const fenced = body.match(/^```(?:json)?\s*([\s\S]*?)```$/i);
  if (fenced) body = fenced[1].trim();
  if (body.startsWith('{') && body.endsWith('}')) return body;
  const start = body.indexOf('{');
  const end = body.lastIndexOf('}');
  return start >= 0 && end > start ? body.slice(start, end + 1) : null;
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
