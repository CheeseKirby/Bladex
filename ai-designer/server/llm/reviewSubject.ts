import { stripPlanContractBlock, type PlanContract } from './planContract';

/**
 * Produces a compact semantic projection. Deterministic validators already own type closure, hash,
 * deliverable topology and schema checks, so those mechanically verified details are intentionally omitted.
 */
export function buildSemanticReviewSubject(planContent: string, contract: PlanContract): string {
  const narrative = stripPlanContractBlock(planContent)
    .replace(/\n*## Canonical implementation contract[\s\S]*$/i, '')
    .trim();
  const projection = {
    contractVersion: contract.contractVersion,
    identity: contract.identity,
    domains: contract.domains,
    modules: contract.modules,
    aggregates: contract.aggregates,
    entities: contract.entities,
    fields: contract.fields.map(({ id, entityId, name, columnName, javaType, required, role, evidence }) => ({
      id, entityId, name, columnName, javaType, required, role,
      ...(evidence ? { evidence: evidence.slice(0, 160) } : {}),
    })),
    states: contract.states,
    integrations: contract.integrations,
    deliverables: contract.deliverables.map(({ id, kind, name, moduleId, className, moduleSide, action }) => ({
      id, kind, name, moduleId, className, moduleSide, action,
    })),
    referenceBindings: contract.referenceBindings,
    architectureDecisions: contract.architectureDecisions,
  };
  return `Plan narrative (explanatory only):\n${narrative || '(none)'}\n\nCanonical semantic projection (source of truth for this review):\n${JSON.stringify(projection)}`;
}
