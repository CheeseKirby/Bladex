import type { ReferenceReviewEvidence } from '../services/referenceSummary';
import type { DeterministicPlanIssue, PlanContract } from './planContract';
import type { ReviewIssuePayload } from './reviewProtocol';

export interface SemanticIssueGateResult {
  issues: DeterministicPlanIssue[];
  acceptedErrorCount: number;
  downgradedErrorCount: number;
}

export function gateSemanticIssues(
  modelIssues: ReviewIssuePayload[],
  deterministicIssues: DeterministicPlanIssue[],
  contract: PlanContract,
  referenceEvidence: ReferenceReviewEvidence,
): SemanticIssueGateResult {
  const deterministicErrors = new Set(deterministicIssues
    .filter((issue) => issue.severity === 'ERROR')
    .map((issue) => normalizeRule(issue.rule)));
  let acceptedErrorCount = 0;
  let downgradedErrorCount = 0;
  const issues = modelIssues.map((issue): DeterministicPlanIssue => {
    const rule = normalizeRule(issue.rule);
    if (issue.severity === 'WARN') return { severity: 'WARN', rule, message: issue.message };
    if (deterministicErrors.has(rule) || verifySemanticInvariant(rule, issue, contract, referenceEvidence)) {
      acceptedErrorCount += 1;
      return { severity: 'ERROR', rule, message: issue.message };
    }
    downgradedErrorCount += 1;
    return {
      severity: 'WARN',
      rule: 'SEMANTIC-CLAIM-UNVERIFIED',
      message: `Model claim ${rule} was not backed by a server-verifiable contract invariant and is advisory only: ${issue.message}`,
    };
  });
  return { issues, acceptedErrorCount, downgradedErrorCount };
}

function verifySemanticInvariant(
  rule: string,
  issue: ReviewIssuePayload,
  contract: PlanContract,
  referenceEvidence: ReferenceReviewEvidence,
): boolean {
  const elementIds = issue.elementIds ?? [];
  const allIds = contractElementIds(contract);
  if (elementIds.some((id) => !allIds.has(id))) return false;
  if (issue.evidence?.source === 'DETERMINISTIC_RULE') return false;

  if (rule === 'FIELD-ENTITY-MISMATCH' || rule === 'FIELD-ROLE-CONTRADICTION'
    || rule === 'FIELD-DDL-ROLE-CONTRADICTION') {
    const field = contract.fields.find((item) => elementIds.includes(item.id));
    if (!field) return false;
    const entity = contract.entities.find((item) => item.id === field.entityId);
    return !entity || !(entity.fieldIds ?? []).includes(field.id);
  }

  if (rule === 'STATE-OWNER-MISMATCH' || rule === 'STATE-OWNERSHIP-MISPLACED') {
    const state = contract.states.find((item) => elementIds.includes(item.id));
    if (!state?.ownerId) return true;
    return !contract.aggregates.some((item) => item.id === state.ownerId)
      && !contract.entities.some((item) => item.id === state.ownerId);
  }

  if (rule === 'INTEGRATION-ENTRY-MISSING') {
    return contract.integrations.some((item) => elementIds.includes(item.id) && !item.entrypoint?.trim());
  }

  if (rule === 'REFERENCE-BINDING-MISSING' || rule === 'REF-DUPLICATE-CAPABILITY') {
    const decision = referenceEvidence.search?.decisions.find((item) => item.decision === 'REUSE' || item.decision === 'EXTEND');
    return Boolean(decision && contract.referenceBindings.length === 0);
  }

  if (rule === 'ARCHITECTURE-DECISION-REQUIRED') {
    return Boolean(referenceEvidence.search?.decisions.some((item) => item.decision === 'ARCHITECTURE_DECISION_REQUIRED'));
  }

  if (rule === 'MISSING-INTEGRATION-DELIVERABLES' || rule === 'MISSING-HOTWORK-DELIVERABLES') {
    return contract.integrations.some((integration) => {
      if (elementIds.length > 0 && !elementIds.includes(integration.id)) return false;
      const delivered = contract.deliverables.some((item) => item.moduleId === integration.targetModule);
      const bound = contract.referenceBindings.some((item) => item.targetModule === integration.targetModule);
      return !delivered && !bound;
    });
  }

  return false;
}

function contractElementIds(contract: PlanContract): Set<string> {
  return new Set([
    ...contract.fields.map((item) => item.id),
    ...contract.domains.map((item) => item.id),
    ...contract.modules.map((item) => item.id),
    ...contract.aggregates.map((item) => item.id),
    ...contract.entities.map((item) => item.id),
    ...contract.states.map((item) => item.id),
    ...contract.integrations.map((item) => item.id),
    ...contract.deliverables.map((item) => item.id),
    ...contract.referenceBindings.map((item) => item.id),
    ...contract.architectureDecisions.map((item) => item.id),
  ]);
}

export function normalizeRule(rule: string): string {
  return rule.trim().toUpperCase().replace(/_/g, '-').replace(/[^A-Z0-9-]+/g, '-').replace(/-+/g, '-');
}
