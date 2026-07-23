import { createHash } from 'node:crypto';
import type { PlanRepairOperation, PlanRepairOperationName } from './planContract';

export const REVIEW_RULESET_VERSION = 'review-v4-reference-relevance';

export type ReviewFinalStatus =
  | 'PASSED'
  | 'PASSED_WITH_WARNINGS'
  | 'BLOCKED'
  | 'REVIEW_INFRA_ERROR';

export type ReviewParseStatus = 'SUCCESS' | 'FAILED';
export type ReviewSchemaValidationStatus = 'SUCCESS' | 'FAILED';

export interface ReviewIssueEvidencePayload {
  source: 'DETERMINISTIC_RULE' | 'CONTRACT_INVARIANT' | 'REFERENCE_DECISION';
  expected?: string;
  actual?: string;
}

export interface ReviewIssuePayload {
  severity: 'ERROR' | 'WARN';
  rule: string;
  message: string;
  elementIds?: string[];
  evidence?: ReviewIssueEvidencePayload;
}

export interface ReviewFixPayload {
  section: string;
  newContent: string;
}

export interface ReviewChangeLogPayload {
  what: string;
  why: string;
  before: string;
  after: string;
}

export interface ValidatedReviewResponse {
  passes: boolean;
  issues: ReviewIssuePayload[];
  repairs: PlanRepairOperation[];
  fixes: ReviewFixPayload[];
  fixedContent?: string;
  changeLog: ReviewChangeLogPayload[];
}

export interface ReviewRoundEvidence {
  round: number;
  receivedAt: string;
  rawResponseLength: number;
  rawResponseSha256: string;
  parseStatus: ReviewParseStatus;
  schemaValidationStatus: ReviewSchemaValidationStatus;
  referenceSummaryAvailable: boolean;
  referenceSnapshotId?: string;
  contractSource?: 'EMBEDDED' | 'INFERRED';
  contractSourceHash?: string;
  deterministicErrorCount?: number;
  deterministicWarningCount?: number;
  responseKind?: 'PRIMARY' | 'SCHEMA_RECOVERY';
  diagnostic?: string;
}

export interface ReviewAuditEvidence {
  reviewId: string;
  rulesetVersion: string;
  startedAt: string;
  completedAt: string;
  rounds: ReviewRoundEvidence[];
}

export type ReviewParseResult =
  | { ok: true; value: ValidatedReviewResponse; evidence: ReviewRoundEvidence }
  | { ok: false; error: string; evidence: ReviewRoundEvidence };

export function parseReviewModelResponse(
  rawResponse: string,
  context: {
    round: number;
    referenceSummaryAvailable: boolean;
    receivedAt?: string;
    referenceSnapshotId?: string;
    contractSource?: 'EMBEDDED' | 'INFERRED';
    contractSourceHash?: string;
    deterministicErrorCount?: number;
    deterministicWarningCount?: number;
    responseKind?: 'PRIMARY' | 'SCHEMA_RECOVERY';
  },
): ReviewParseResult {
  const receivedAt = context.receivedAt ?? new Date().toISOString();
  const raw = typeof rawResponse === 'string' ? rawResponse : '';
  const evidenceBase = {
    round: context.round,
    receivedAt,
    rawResponseLength: raw.length,
    rawResponseSha256: createHash('sha256').update(raw, 'utf8').digest('hex'),
    referenceSummaryAvailable: context.referenceSummaryAvailable,
    ...(context.referenceSnapshotId ? { referenceSnapshotId: context.referenceSnapshotId } : {}),
    ...(context.contractSource ? { contractSource: context.contractSource } : {}),
    ...(context.contractSourceHash ? { contractSourceHash: context.contractSourceHash } : {}),
    ...(context.deterministicErrorCount != null ? { deterministicErrorCount: context.deterministicErrorCount } : {}),
    ...(context.deterministicWarningCount != null ? { deterministicWarningCount: context.deterministicWarningCount } : {}),
    ...(context.responseKind ? { responseKind: context.responseKind } : {}),
  };

  const jsonText = extractJsonObject(raw);
  if (!jsonText) {
    const diagnostic = raw.trim() ? 'LLM response does not contain a JSON object' : 'LLM response is empty';
    return {
      ok: false,
      error: diagnostic,
      evidence: {
        ...evidenceBase,
        parseStatus: 'FAILED',
        schemaValidationStatus: 'FAILED',
        diagnostic,
      },
    };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(jsonText);
  } catch (error) {
    const diagnostic = `LLM review JSON parse failed: ${errorMessage(error)}`;
    return {
      ok: false,
      error: diagnostic,
      evidence: {
        ...evidenceBase,
        parseStatus: 'FAILED',
        schemaValidationStatus: 'FAILED',
        diagnostic,
      },
    };
  }

  const validated = validateReviewShape(parsed);
  if (!validated.ok) {
    return {
      ok: false,
      error: validated.error,
      evidence: {
        ...evidenceBase,
        parseStatus: 'SUCCESS',
        schemaValidationStatus: 'FAILED',
        diagnostic: validated.error,
      },
    };
  }

  return {
    ok: true,
    value: validated.value,
    evidence: {
      ...evidenceBase,
      parseStatus: 'SUCCESS',
      schemaValidationStatus: 'SUCCESS',
    },
  };
}

function validateReviewShape(value: unknown):
  | { ok: true; value: ValidatedReviewResponse }
  | { ok: false; error: string } {
  if (!isRecord(value)) return invalid('LLM review response must be a JSON object');
  if (typeof value.passes !== 'boolean') return invalid('LLM review response.passes must be boolean');
  if (!Array.isArray(value.issues)) return invalid('LLM review response.issues must be an array');
  if (value.issues.length > 20) return invalid('LLM review response.issues must contain at most 20 entries');

  const issues: ReviewIssuePayload[] = [];
  for (let index = 0; index < value.issues.length; index += 1) {
    const issue = value.issues[index];
    if (!isRecord(issue)) return invalid(`LLM review response.issues[${index}] must be an object`);
    if (issue.severity !== 'ERROR' && issue.severity !== 'WARN') {
      return invalid(`LLM review response.issues[${index}].severity must be ERROR or WARN`);
    }
    const rule = nonBlankString(issue.rule);
    const message = nonBlankString(issue.message);
    if (!rule) return invalid(`LLM review response.issues[${index}].rule must be a non-empty string`);
    if (!message) return invalid(`LLM review response.issues[${index}].message must be a non-empty string`);
    let elementIds: string[] | undefined;
    if (issue.elementIds != null) {
      if (!Array.isArray(issue.elementIds) || !issue.elementIds.every((item) => nonBlankString(item))) {
        return invalid(`LLM review response.issues[${index}].elementIds must be an array of non-empty strings`);
      }
      elementIds = issue.elementIds.map((item) => String(item).trim());
    }
    let evidence: ReviewIssueEvidencePayload | undefined;
    if (issue.evidence != null) {
      if (!isRecord(issue.evidence)
        || (issue.evidence.source !== 'DETERMINISTIC_RULE'
          && issue.evidence.source !== 'CONTRACT_INVARIANT'
          && issue.evidence.source !== 'REFERENCE_DECISION')
        || (issue.evidence.expected != null && typeof issue.evidence.expected !== 'string')
        || (issue.evidence.actual != null && typeof issue.evidence.actual !== 'string')) {
        return invalid(`LLM review response.issues[${index}].evidence is invalid`);
      }
      evidence = {
        source: issue.evidence.source,
        ...(typeof issue.evidence.expected === 'string' ? { expected: issue.evidence.expected } : {}),
        ...(typeof issue.evidence.actual === 'string' ? { actual: issue.evidence.actual } : {}),
      };
    }
    issues.push({ severity: issue.severity, rule, message,
      ...(elementIds ? { elementIds } : {}), ...(evidence ? { evidence } : {}) });
  }

  if (!Array.isArray(value.repairs)) return invalid('LLM review response.repairs must be an array');
  if (value.repairs.length > 20) return invalid('LLM review response.repairs must contain at most 20 entries');
  const repairs: PlanRepairOperation[] = [];
  for (let index = 0; index < value.repairs.length; index += 1) {
    const repair = value.repairs[index];
    if (!isRecord(repair)) return invalid(`LLM review response.repairs[${index}] must be an object`);
    if (!isPlanRepairOperationName(repair.operation)) {
      return invalid(`LLM review response.repairs[${index}].operation is not supported`);
    }
    if (repair.targetId != null && !nonBlankString(repair.targetId)) {
      return invalid(`LLM review response.repairs[${index}].targetId must be a non-empty string when present`);
    }
    if (!isRecord(repair.arguments)) {
      return invalid(`LLM review response.repairs[${index}].arguments must be an object`);
    }
    if (!isStringArray(repair.resolves) || repair.resolves.length === 0) {
      return invalid(`LLM review response.repairs[${index}].resolves must be a non-empty string array`);
    }
    if (!isStringArray(repair.preconditions) || repair.preconditions.length === 0) {
      return invalid(`LLM review response.repairs[${index}].preconditions must be a non-empty string array`);
    }
    repairs.push({
      operation: repair.operation,
      ...(typeof repair.targetId === 'string' ? { targetId: repair.targetId.trim() } : {}),
      arguments: repair.arguments,
      resolves: repair.resolves,
      preconditions: repair.preconditions,
    });
  }

  if (!Array.isArray(value.fixes)) return invalid('LLM review response.fixes must be an array');
  if (value.fixes.length > 6) return invalid('LLM review response.fixes must contain at most 6 entries');
  const fixesValue = value.fixes;
  const fixes: ReviewFixPayload[] = [];
  for (let index = 0; index < fixesValue.length; index += 1) {
    const fix = fixesValue[index];
    if (!isRecord(fix)) return invalid(`LLM review response.fixes[${index}] must be an object`);
    const section = nonBlankString(fix.section);
    const newContent = nonBlankString(fix.newContent);
    if (!section) return invalid(`LLM review response.fixes[${index}].section must be a non-empty string`);
    if (!newContent) return invalid(`LLM review response.fixes[${index}].newContent must be a non-empty string`);
    fixes.push({ section, newContent });
  }

  if (value.fixedContent != null && typeof value.fixedContent !== 'string') {
    return invalid('LLM review response.fixedContent must be a string when present');
  }

  if (!Array.isArray(value.changeLog)) {
    return invalid('LLM review response.changeLog must be an array');
  }
  const changeLogValue = value.changeLog;
  const changeLog: ReviewChangeLogPayload[] = [];
  for (let index = 0; index < changeLogValue.length; index += 1) {
    const entry = changeLogValue[index];
    if (!isRecord(entry)) return invalid(`LLM review response.changeLog[${index}] must be an object`);
    const what = stringValue(entry.what);
    const why = stringValue(entry.why);
    const before = stringValue(entry.before);
    const after = stringValue(entry.after);
    if (what == null || why == null || before == null || after == null) {
      return invalid(`LLM review response.changeLog[${index}] fields must be strings`);
    }
    changeLog.push({ what, why, before, after });
  }

  const hasError = issues.some((issue) => issue.severity === 'ERROR');
  if (value.passes === hasError) return invalid('LLM review response.passes contradicts ERROR issues');
  if (value.passes && (repairs.length > 0 || fixes.length > 0)) {
    return invalid('Passing review must not contain repairs or fixes');
  }

  return {
    ok: true,
    value: {
      passes: value.passes,
      issues,
      repairs,
      fixes,
      ...(typeof value.fixedContent === 'string' ? { fixedContent: value.fixedContent } : {}),
      changeLog,
    },
  };
}

export async function parseReviewModelResponseWithRecovery(
  rawResponse: string,
  context: Parameters<typeof parseReviewModelResponse>[1],
  recover: (rawResponse: string, error: string) => Promise<string>,
): Promise<
  | { ok: true; value: ValidatedReviewResponse; evidence: ReviewRoundEvidence[]; recovered: boolean }
  | { ok: false; error: string; evidence: ReviewRoundEvidence[]; recovered: boolean }
> {
  const primary = parseReviewModelResponse(rawResponse, { ...context, responseKind: 'PRIMARY' });
  if (primary.ok) return { ok: true, value: primary.value, evidence: [primary.evidence], recovered: false };

  let recoveredRaw: string;
  try {
    recoveredRaw = await recover(rawResponse, primary.error);
  } catch (error) {
    return {
      ok: false,
      error: `Review schema recovery call failed: ${errorMessage(error)}`,
      evidence: [primary.evidence],
      recovered: true,
    };
  }
  const recovered = parseReviewModelResponse(recoveredRaw, { ...context, responseKind: 'SCHEMA_RECOVERY' });
  if (!recovered.ok) {
    return {
      ok: false,
      error: `Primary response invalid (${primary.error}); schema recovery invalid (${recovered.error})`,
      evidence: [primary.evidence, recovered.evidence],
      recovered: true,
    };
  }
  return { ok: true, value: recovered.value, evidence: [primary.evidence, recovered.evidence], recovered: true };
}

function extractJsonObject(raw: string): string | null {
  let body = raw.trim();
  if (!body) return null;
  const fenced = body.match(/^```(?:json)?\s*([\s\S]*?)```$/i);
  if (fenced) body = fenced[1].trim();
  if (body.startsWith('{') && body.endsWith('}')) return body;
  const start = body.indexOf('{');
  const end = body.lastIndexOf('}');
  return start >= 0 && end > start ? body.slice(start, end + 1) : null;
}

function invalid(error: string): { ok: false; error: string } {
  return { ok: false, error };
}


const PLAN_REPAIR_OPERATIONS = new Set<PlanRepairOperationName>([
  'ADD_MODULE',
  'MOVE_ENTITY',
  'SPLIT_AGGREGATE',
  'BIND_EXISTING_SYMBOL',
  'ADD_REFERENCE_BINDING',
  'ADD_DELIVERABLE',
  'ADD_INTEGRATION',
  'CHANGE_STATE_OWNER',
  'DECLARE_ARCHITECTURE_DECISION',
  'RENAME_ENTITY',
]);

function isPlanRepairOperationName(value: unknown): value is PlanRepairOperationName {
  return typeof value === 'string' && PLAN_REPAIR_OPERATIONS.has(value as PlanRepairOperationName);
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonBlankString(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed || null;
}

function stringValue(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
