import {
  PLAN_CONTRACT_RULESET_VERSION,
  PLAN_CONTRACT_VERSION,
  hashPlanContent,
  renderCanonicalContractSummary,
  upsertPlanContractBlock,
  type PlanArchitectureDecision,
  type DeterministicPlanIssue,
  type PlanContract,
  type PlanDeliverableContract,
  type PlanFieldContract,
  type PlanIntegrationContract,
  type PlanStateContract,
} from './planContract';

export interface PlanDraftV2 {
  identity: {
    moduleName: string;
    entityName: string;
    tableName: string;
    basePackage: string;
  };
  title: string;
  requirementSummary: string;
  fields: Array<{
    name: string;
    columnName: string;
    javaType: string;
    required: boolean;
    role: 'PERSISTENT' | 'DERIVED';
    description: string;
  }>;
  states: Array<{
    name: string;
    values: string[];
    transitions: Array<{ from: string; to: string; trigger: string }>;
    referenceField?: string;
  }>;
  integrations: Array<{
    type: PlanIntegrationContract['type'];
    sourceModule?: string;
    targetModule?: string;
    entrypoint: string;
  }>;
  deliverables: Array<{
    kind: PlanDeliverableContract['kind'];
    className?: string;
    moduleSide: NonNullable<PlanDeliverableContract['moduleSide']>;
    action: NonNullable<PlanDeliverableContract['action']>;
  }>;
  architectureDecisions: Array<{ decision: string; rationale: string; evidence: string[] }>;
}


const PLAN_DRAFT_MECHANICAL_ERROR_RULES = new Set([
  'DELIVERABLE-MISSING',
  'DELIVERABLE-TYPE-DUPLICATE',
  'DELIVERABLE-TYPE-SHAPE-INVALID',
  'DELIVERABLE-CLASS-NOT-PROVIDED',
  'TYPE-PROVIDER-DUPLICATE',
  'TYPE-PROVIDER-MISSING',
  'STATE-OWNERSHIP-UNDEFINED',
  'PLAN-MULTI-ENTITY-UNSUPPORTED',
]);

export function isPlanDraftGenerationBlockingIssue(issue: DeterministicPlanIssue): boolean {
  return issue.severity === 'ERROR' && (
    issue.rule.startsWith('PLAN-CONTRACT')
    || issue.rule.startsWith('PLAN-IDENTITY')
    || PLAN_DRAFT_MECHANICAL_ERROR_RULES.has(issue.rule)
  );
}

export type PlanDraftParseResult = { ok: true; value: PlanDraftV2 } | { ok: false; error: string };

export function parsePlanDraftResponse(raw: string): PlanDraftParseResult {
  const json = extractJsonObject(raw);
  if (!json) return { ok: false, error: 'Plan draft response is empty or does not contain a JSON object' };
  let value: unknown;
  try { value = JSON.parse(json); } catch (error) {
    return { ok: false, error: `Plan draft JSON parse failed: ${errorMessage(error)}` };
  }
  if (!isRecord(value) || !isRecord(value.identity)) return invalid('identity is required');
  const identity = value.identity;
  const identityKeys = ['moduleName', 'entityName', 'tableName', 'basePackage'] as const;
  if (!identityKeys.every((key) => nonBlankString(identity[key]))) return invalid('identity fields must be non-empty strings');
  if (!nonBlankString(value.title) || !nonBlankString(value.requirementSummary)) return invalid('title and requirementSummary are required');
  if (!Array.isArray(value.fields) || value.fields.length === 0) return invalid('fields must be a non-empty array');
  const fields: PlanDraftV2['fields'] = [];
  for (const [index, field] of value.fields.entries()) {
    if (!isRecord(field) || !nonBlankString(field.name) || !nonBlankString(field.columnName)
      || !nonBlankString(field.javaType) || typeof field.required !== 'boolean'
      || (field.role !== 'PERSISTENT' && field.role !== 'DERIVED') || !nonBlankString(field.description)) {
      return invalid(`fields[${index}] is invalid`);
    }
    fields.push(field as unknown as PlanDraftV2['fields'][number]);
  }
  if (new Set(fields.map((field) => field.name)).size !== fields.length) return invalid('field names must be unique');
  if (!Array.isArray(value.states) || !Array.isArray(value.integrations)
    || !Array.isArray(value.deliverables) || !Array.isArray(value.architectureDecisions)) {
    return invalid('states, integrations, deliverables and architectureDecisions must be arrays');
  }
  const states: PlanDraftV2['states'] = [];
  for (const [index, state] of value.states.entries()) {
    if (!isRecord(state) || !nonBlankString(state.name) || !isStringArray(state.values)
      || !Array.isArray(state.transitions) || !state.transitions.every(isTransition)) return invalid(`states[${index}] is invalid`);
    if (state.referenceField != null && typeof state.referenceField !== 'string') return invalid(`states[${index}].referenceField is invalid`);
    states.push(state as unknown as PlanDraftV2['states'][number]);
  }
  const integrations: PlanDraftV2['integrations'] = [];
  for (const [index, integration] of value.integrations.entries()) {
    if (!isRecord(integration) || !['API', 'FEIGN', 'WORKFLOW', 'EVENT', 'OTHER'].includes(String(integration.type))
      || !nonBlankString(integration.entrypoint)) return invalid(`integrations[${index}] is invalid`);
    if (integration.sourceModule != null && typeof integration.sourceModule !== 'string') return invalid(`integrations[${index}].sourceModule is invalid`);
    if (integration.targetModule != null && typeof integration.targetModule !== 'string') return invalid(`integrations[${index}].targetModule is invalid`);
    integrations.push(integration as unknown as PlanDraftV2['integrations'][number]);
  }
  const deliverables: PlanDraftV2['deliverables'] = [];
  for (const [index, deliverable] of value.deliverables.entries()) {
    if (!isRecord(deliverable)
      || !['DDL', 'ENTITY', 'VO', 'MAPPER', 'SERVICE', 'WRAPPER', 'CONTROLLER', 'FEIGN', 'EXCEL', 'CONFIG', 'OTHER'].includes(String(deliverable.kind))
      || !['API', 'IMPL', 'DOC', 'UNKNOWN'].includes(String(deliverable.moduleSide))
      || !['CREATE', 'MODIFY', 'EXTEND', 'PROHIBIT'].includes(String(deliverable.action))) return invalid(`deliverables[${index}] is invalid`);
    if (deliverable.className != null && typeof deliverable.className !== 'string') return invalid(`deliverables[${index}].className is invalid`);
    deliverables.push(deliverable as unknown as PlanDraftV2['deliverables'][number]);
  }
  const requiredKinds = ['DDL', 'ENTITY', 'VO', 'MAPPER', 'SERVICE', 'CONTROLLER'];
  const kinds = new Set(deliverables.filter((item) => item.action !== 'PROHIBIT').map((item) => item.kind));
  const missing = requiredKinds.filter((kind) => !kinds.has(kind as PlanDeliverableContract['kind']));
  if (missing.length > 0) return invalid(`required deliverables are missing: ${missing.join(', ')}`);
  const architectureDecisions: PlanDraftV2['architectureDecisions'] = [];
  for (const [index, decision] of value.architectureDecisions.entries()) {
    if (!isRecord(decision) || !nonBlankString(decision.decision) || !nonBlankString(decision.rationale)
      || !isStringArray(decision.evidence)) return invalid(`architectureDecisions[${index}] is invalid`);
    architectureDecisions.push(decision as unknown as PlanDraftV2['architectureDecisions'][number]);
  }
  return {
    ok: true,
    value: {
      identity: identity as unknown as PlanDraftV2['identity'],
      title: value.title.trim(), requirementSummary: value.requirementSummary.trim(),
      fields, states, integrations, deliverables, architectureDecisions,
    },
  };
}

export function normalizePlanDraftAgainstRequirement(draft: PlanDraftV2, requirement: string): PlanDraftV2 {
  if (draft.states.length === 0 || hasGroundedStateIntent(draft, requirement)) return draft;
  const removedValues = new Set(draft.states.flatMap((state) => state.values.map((value) => value.toLowerCase())));
  return {
    ...draft,
    states: [],
    architectureDecisions: draft.architectureDecisions.filter((item) => {
      const text = `${item.decision} ${item.rationale}`.toLowerCase();
      return !/state machine|state values?|state transitions?|status transitions?|\u72b6\u6001\u673a|\u72b6\u6001\u503c|\u72b6\u6001\u6d41\u8f6c|\u72b6\u6001\u8f6c\u6362/.test(text)
        && !Array.from(removedValues).some((value) => value && text.includes(value));
    }),
  };
}

function hasGroundedStateIntent(draft: PlanDraftV2, requirement: string): boolean {
  const normalized = requirement.toLowerCase();
  if (/state machine|status transitions?|state transitions?|workflow|\u72b6\u6001\u673a|\u72b6\u6001\u6d41\u8f6c|\u72b6\u6001\u8f6c\u6362|\u5de5\u4f5c\u6d41/.test(normalized)
    && !/no workflow|without workflow|\u4e0d\u9700\u5de5\u4f5c\u6d41|\u65e0\u5de5\u4f5c\u6d41/.test(normalized)) return true;
  const values = Array.from(new Set(draft.states.flatMap((state) => state.values.map((value) => value.trim().toLowerCase())).filter(Boolean)));
  return values.length >= 2 && values.every((value) => normalized.includes(value));
}

export function compileStructuredPlanDraft(draft: PlanDraftV2, referenceSnapshotId?: string): PlanContract {
  const moduleName = normalizeModuleName(draft.identity.moduleName);
  const entityId = `entity.${toId(draft.identity.entityName)}`;
  const moduleId = `module.${toId(moduleName)}`;
  const domainId = `domain.${toId(moduleName)}`;
  const aggregateId = `aggregate.${toId(draft.identity.entityName)}`;
  const identity = {
    moduleName,
    entityName: draft.identity.entityName,
    tableName: draft.identity.tableName,
    basePackage: draft.identity.basePackage,
    apiModuleName: `blade-${moduleName}-api`,
    serviceModuleName: `blade-${moduleName}`,
    serviceName: `blade-${moduleName}`,
  };
  const fields: PlanFieldContract[] = draft.fields.map((field) => ({
    id: `field.${toId(draft.identity.entityName)}.${toId(field.name)}`,
    entityId,
    name: field.name,
    columnName: field.columnName,
    javaType: field.javaType,
    required: field.required,
    role: field.role,
    evidence: field.description,
  }));
  const states: PlanStateContract[] = draft.states.map((state, index) => ({
    id: `state.${toId(state.name) || index + 1}`,
    name: state.name,
    ownerId: aggregateId,
    values: state.values,
    transitions: state.transitions,
    ...(state.referenceField ? { referenceField: state.referenceField } : {}),
  }));
  const integrations: PlanIntegrationContract[] = draft.integrations.map((item, index) => ({
    id: `integration.${toId(item.type)}.${index + 1}`,
    type: item.type,
    sourceModule: item.sourceModule || moduleName,
    ...(item.targetModule ? { targetModule: item.targetModule } : {}),
    entrypoint: item.entrypoint,
  }));
  const deliverables = enrichDraftDeliverables(draft.deliverables, identity.entityName, moduleId);
  const architectureDecisions: PlanArchitectureDecision[] = draft.architectureDecisions
    .filter((item) => !isArtifactInventoryDecision(item))
    .map((item, index) => ({ id: `adr.${index + 1}`, ...item }));
  const contract: PlanContract = {
    contractVersion: PLAN_CONTRACT_VERSION,
    sourceHash: '0'.repeat(64),
    sourceMode: 'STRUCTURED',
    ...(referenceSnapshotId ? { referenceSnapshotId } : {}),
    rulesetVersion: PLAN_CONTRACT_RULESET_VERSION,
    identity,
    fields,
    domains: [{ id: domainId, name: moduleName, ownerModuleIds: [moduleId] }],
    modules: [{ id: moduleId, name: moduleName, basePackage: identity.basePackage, kind: 'NEW' }],
    aggregates: [{ id: aggregateId, name: `${identity.entityName}Aggregate`, domainId, rootEntityId: entityId }],
    entities: [{ id: entityId, name: identity.entityName, moduleId, aggregateId, table: identity.tableName,
      fields: fields.map((field) => field.name), fieldIds: fields.map((field) => field.id) }],
    states,
    integrations,
    deliverables,
    referenceBindings: [],
    architectureDecisions,
  };
  const markdown = renderDraftNarrative(draft, contract);
  contract.sourceHash = hashPlanContent(markdown);
  return contract;
}

export function renderStructuredPlan(draft: PlanDraftV2, contract: PlanContract): string {
  const narrative = renderDraftNarrative(draft, contract);
  contract.sourceHash = hashPlanContent(narrative);
  return renderCanonicalContractSummary(narrative, contract);
}

function renderDraftNarrative(draft: PlanDraftV2, contract: PlanContract): string {
  const persistent = contract.fields.filter((field) => field.role === 'PERSISTENT');
  const states = contract.states.length > 0 ? contract.states.map((state) =>
    `- ${state.name}: ${state.values.join(' -> ') || '(values to be confirmed)'}`).join('\n') : '- none';
  const integrations = contract.integrations.length > 0 ? contract.integrations.map((item) =>
    `- ${item.type}: ${item.sourceModule ?? contract.identity.moduleName} -> ${item.targetModule ?? '(local)'}, entry=${item.entrypoint}`).join('\n') : '- none';
  return `# ${draft.title}\n\n## Requirement analysis\n\n${draft.requirementSummary}\n\n## Identity\n\n- moduleName: ${contract.identity.moduleName}\n- entityName: ${contract.identity.entityName}\n- tableName: ${contract.identity.tableName}\n- basePackage: ${contract.identity.basePackage}\n\n## Module structure\n\n- blade-service-api/${contract.identity.apiModuleName}\n- blade-service/${contract.identity.serviceModuleName}\n\n## Database and Entity fields\n\n| Field | Column | Type | Required | Description |\n|---|---|---|---|---|\n${persistent.map((field) => `| ${field.name} | ${field.columnName} | ${field.javaType} | ${field.required ? 'yes' : 'no'} | ${field.evidence ?? ''} |`).join('\n')}\n\n## State model\n\n${states}\n\n## Integrations\n\n${integrations}\n\n## Deliverables\n\n${contract.deliverables.map((item) => `- ${item.id}: ${item.kind}${item.className ? ` (${item.className})` : ''}`).join('\n')}\n\n## Implementation order\n\n${contract.deliverables.map((item, index) => `${index + 1}. ${item.id}`).join('\n')}`;
}

function isArtifactInventoryDecision(item: PlanDraftV2['architectureDecisions'][number]): boolean {
  return /\b(?:QVO|IVO|UVO|EVO|VO|EXCEL|FEIGN)\b/i.test(`${item.decision} ${item.rationale}`);
}

function enrichDraftDeliverables(
  items: PlanDraftV2['deliverables'], entityName: string, moduleId: string,
): PlanDeliverableContract[] {
  const normalized = normalizeDraftDeliverables(items, entityName);
  const serviceInterface = `I${entityName}Service`;
  const kindCounters = new Map<PlanDeliverableContract['kind'], number>();
  const deliverables = normalized.map((item) => {
    const ordinal = (kindCounters.get(item.kind) ?? 0) + 1;
    kindCounters.set(item.kind, ordinal);
    const className = item.className?.trim();
    const providesTypes = item.providesTypes ?? providedTypesFor(item.kind, className, entityName);
    const requiresTypes: Record<PlanDeliverableContract['kind'], string[]> = {
      DDL: [],
      ENTITY: [],
      VO: [entityName],
      MAPPER: [entityName, `${entityName}VO`, `${entityName}QVO`],
      SERVICE: [entityName, `${entityName}VO`, `${entityName}QVO`, `${entityName}IVO`, `${entityName}UVO`],
      WRAPPER: [entityName, `${entityName}VO`],
      CONTROLLER: [serviceInterface, `${entityName}Wrapper`, `${entityName}VO`, `${entityName}QVO`, `${entityName}IVO`, `${entityName}UVO`],
      FEIGN: [`${entityName}VO`],
      EXCEL: [entityName],
      CONFIG: [],
      OTHER: [],
    };
    return {
      id: `deliverable.${item.kind.toLowerCase()}.${ordinal}`,
      kind: item.kind,
      name: className || item.kind,
      moduleId,
      ...(className ? { className } : {}),
      moduleSide: canonicalModuleSide(item),
      action: item.action,
      providesTypes,
      requiresTypes: requiresTypes[item.kind],
    } satisfies PlanDeliverableContract;
  });
  assertUniqueActiveTypeProviders(deliverables);
  return deliverables;
}

type NormalizedDraftDeliverable = PlanDraftV2['deliverables'][number] & { providesTypes?: string[] };

function normalizeDraftDeliverables(
  items: PlanDraftV2['deliverables'], entityName: string,
): NormalizedDraftDeliverable[] {
  const active = items.filter((item) => item.action !== 'PROHIBIT');
  const standardVoTypes = [`${entityName}VO`, `${entityName}QVO`, `${entityName}IVO`, `${entityName}UVO`];
  const canonicalVoTypes = standardVoTypes;
  const activeVos = active.filter((item) => item.kind === 'VO');
  const activeServices = active.filter((item) => item.kind === 'SERVICE');
  const activeEntities = active.filter((item) => item.kind === 'ENTITY');
  const activeDdls = active.filter((item) => item.kind === 'DDL');
  const activeMappers = active.filter((item) => item.kind === 'MAPPER');
  const seen = new Set<string>();
  const result: NormalizedDraftDeliverable[] = [];
  let voInserted = false;
  let serviceInserted = false;
  let entityInserted = false;
  let ddlInserted = false;
  let mapperInserted = false;
  let wrapperInserted = false;

  const pushUnique = (item: NormalizedDraftDeliverable) => {
    const key = `${item.kind}|${item.className?.trim() ?? ''}|${item.action}`;
    if (seen.has(key)) return;
    seen.add(key);
    result.push(item);
  };

  for (const item of items) {
    if (item.action === 'PROHIBIT') {
      pushUnique({ ...item, className: item.className?.trim() || undefined, providesTypes: [] });
      continue;
    }
    if (item.kind === 'DDL') {
      if (!ddlInserted) {
        pushUnique({ ...representative(activeDdls, item, 'DDL'), className: undefined, providesTypes: [] });
        ddlInserted = true;
      }
      continue;
    }
    if (item.kind === 'ENTITY') {
      if (!entityInserted) {
        pushUnique({ ...representative(activeEntities, item, 'ENTITY'), className: entityName, providesTypes: [entityName] });
        entityInserted = true;
      }
      continue;
    }
    if (item.kind === 'VO') {
      if (voInserted) continue;
      const canonicalSource = activeVos.find((candidate) => standardVoTypes.includes(candidate.className?.trim() || ''))
        ?? activeVos[0] ?? item;
      pushUnique({ ...canonicalSource, className: `${entityName}VO`, providesTypes: canonicalVoTypes });
      for (const custom of activeVos) {
        const customName = custom.className?.trim();
        if (!customName || standardVoTypes.includes(customName)) continue;
        if (!customName.endsWith('VO')) {
          throw new Error(`PLAN_DRAFT_DELIVERABLE_CONFLICT: VO class ${customName} must end with VO`);
        }
        pushUnique({ ...custom, className: customName, providesTypes: [customName] });
      }
      voInserted = true;
      continue;
    }
    if (item.kind === 'MAPPER') {
      if (!mapperInserted) {
        const mapperSource = representative(activeMappers, item, 'MAPPER');
        pushUnique({ ...mapperSource, className: `${entityName}Mapper`, providesTypes: [`${entityName}Mapper`] });
        mapperInserted = true;
      }
      continue;
    }
    if (item.kind === 'WRAPPER') {
      if (!wrapperInserted) {
        pushUnique({ ...item, className: `${entityName}Wrapper`, providesTypes: [`${entityName}Wrapper`] });
        wrapperInserted = true;
      }
      continue;
    }
    if (item.kind === 'CONTROLLER' && !wrapperInserted) {
      pushUnique({ kind: 'WRAPPER', className: `${entityName}Wrapper`, moduleSide: 'IMPL', action: item.action, providesTypes: [`${entityName}Wrapper`] });
      wrapperInserted = true;
    }
    if (item.kind === 'SERVICE') {
      if (!serviceInserted) {
        const serviceSource = representative(activeServices, item, 'SERVICE');
        pushUnique({
          ...serviceSource,
          className: `I${entityName}Service`,
          providesTypes: [`I${entityName}Service`, `${entityName}ServiceImpl`],
        });
        serviceInserted = true;
      }
      continue;
    }
    const className = normalizedClassName(item.kind, item.className?.trim(), entityName);
    pushUnique({ ...item, ...(className ? { className } : {}), providesTypes: providedTypesFor(item.kind, className, entityName) });
  }
  return result;
}

function representative(
  candidates: PlanDraftV2['deliverables'], fallback: PlanDraftV2['deliverables'][number], kind: string,
): PlanDraftV2['deliverables'][number] {
  const first = candidates[0] ?? fallback;
  const actions = new Set(candidates.map((candidate) => candidate.action));
  if (actions.size > 1) {
    throw new Error(`PLAN_DRAFT_DELIVERABLE_CONFLICT: ${kind} declarations have incompatible actions`);
  }
  return first;
}

function normalizedClassName(
  kind: PlanDeliverableContract['kind'], className: string | undefined, entityName: string,
): string | undefined {
  switch (kind) {
    case 'WRAPPER': return className?.endsWith('Wrapper') ? className : `${entityName}Wrapper`;
    case 'CONTROLLER': return className?.endsWith('Controller') ? className : `${entityName}Controller`;
    case 'FEIGN': return className || `I${entityName}Client`;
    case 'EXCEL': return className || `${entityName}Excel`;
    case 'OTHER': return className;
    default: return undefined;
  }
}

function providedTypesFor(
  kind: PlanDeliverableContract['kind'], className: string | undefined, entityName: string,
): string[] {
  switch (kind) {
    case 'ENTITY': return [className || entityName];
    case 'VO': return className ? [className] : [`${entityName}VO`];
    case 'MAPPER': return [className || `${entityName}Mapper`];
    case 'SERVICE': return [`I${entityName}Service`, `${entityName}ServiceImpl`];
    case 'WRAPPER': return [className || `${entityName}Wrapper`];
    case 'CONTROLLER': return [className || `${entityName}Controller`];
    case 'FEIGN': return [className || `I${entityName}Client`];
    case 'EXCEL': return [className || `${entityName}Excel`];
    case 'OTHER': return className ? [className] : [];
    default: return [];
  }
}

function canonicalModuleSide(item: NormalizedDraftDeliverable): NonNullable<PlanDeliverableContract['moduleSide']> {
  switch (item.kind) {
    case 'DDL': return 'DOC';
    case 'ENTITY':
    case 'VO':
    case 'FEIGN': return 'API';
    case 'MAPPER':
    case 'SERVICE':
    case 'WRAPPER':
    case 'CONTROLLER': return 'IMPL';
    default: return item.moduleSide;
  }
}

function assertUniqueActiveTypeProviders(deliverables: PlanDeliverableContract[]): void {
  const providers = new Map<string, string>();
  for (const deliverable of deliverables) {
    if (deliverable.action === 'PROHIBIT') continue;
    for (const type of deliverable.providesTypes ?? []) {
      const existing = providers.get(type);
      if (existing && existing !== deliverable.id) {
        throw new Error(`PLAN_DRAFT_DELIVERABLE_CONFLICT: Type ${type} is provided by both ${existing} and ${deliverable.id}`);
      }
      providers.set(type, deliverable.id);
    }
  }
}

function extractJsonObject(raw: string): string | null {
  let body = raw.trim();
  const fenced = body.match(/^```(?:json)?\s*([\s\S]*?)```$/i);
  if (fenced) body = fenced[1].trim();
  if (body.startsWith('{') && body.endsWith('}')) return body;
  const start = body.indexOf('{'); const end = body.lastIndexOf('}');
  return start >= 0 && end > start ? body.slice(start, end + 1) : null;
}
function invalid(error: string): PlanDraftParseResult { return { ok: false, error }; }
function isTransition(value: unknown): boolean {
  return isRecord(value) && nonBlankString(value.from) && nonBlankString(value.to) && nonBlankString(value.trigger);
}
function isStringArray(value: unknown): value is string[] { return Array.isArray(value) && value.every((item) => typeof item === 'string'); }
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value); }
function nonBlankString(value: unknown): value is string { return typeof value === 'string' && value.trim().length > 0; }
function normalizeModuleName(value: string): string { return value.toLowerCase().replace(/^blade-/, '').replace(/-api$/, '').trim(); }
function toId(value: string): string { return value.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/[^A-Za-z0-9]+/g, '-').toLowerCase().replace(/^-|-$/g, ''); }
function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
