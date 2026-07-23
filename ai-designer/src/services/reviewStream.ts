import type {
  ChangeLogEntry,
  ReviewAuditEvidence,
  ReviewFinalStatus,
  ReviewIssue,
  ReviewLogEntry,
} from '../types/plan';

export interface ReviewStreamResult {
  reviewId: string;
  contractHash: string;
  status: ReviewFinalStatus;
  passes: boolean;
  issues: ReviewIssue[];
  reviewLog: ReviewLogEntry[];
  fixedContent: string;
  changeLog: ChangeLogEntry[];
  audit: ReviewAuditEvidence;
  cacheHit?: boolean;
}

export type ReviewProgressStage = 'preparing' | 'reviewing' | 'analyzing' | 'fixing' | 'complete';

export interface ReviewProgressEvent {
  message: string;
  stage?: ReviewProgressStage;
  round?: number;
  totalRounds?: number;
  errorCount?: number;
  warningCount?: number;
  sequence?: number;
}

interface ReviewEvent extends Partial<ReviewProgressEvent> {
  type?: string;
  code?: string;
  audit?: unknown;
  data?: unknown;
}

export class ReviewStreamError extends Error {
  constructor(
    message: string,
    public readonly code = 'REVIEW_INFRA_ERROR',
    public readonly audit?: ReviewAuditEvidence,
  ) {
    super(message);
    this.name = 'ReviewStreamError';
  }
}

export async function consumeReviewStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onProgress: (event: ReviewProgressEvent) => void,
): Promise<ReviewStreamResult> {
  const decoder = new TextDecoder();
  let buffer = '';

  const processFrame = (frame: string): ReviewStreamResult | null => {
    for (const line of frame.split('\n')) {
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (!payload) continue;

      let event: ReviewEvent;
      try {
        event = JSON.parse(payload) as ReviewEvent;
      } catch {
        continue;
      }

      if (event.type === 'progress') {
        if (typeof event.message === 'string') {
          onProgress({
            message: event.message,
            ...(isProgressStage(event.stage) ? { stage: event.stage } : {}),
            ...(typeof event.round === 'number' ? { round: event.round } : {}),
            ...(typeof event.totalRounds === 'number' ? { totalRounds: event.totalRounds } : {}),
            ...(typeof event.errorCount === 'number' ? { errorCount: event.errorCount } : {}),
            ...(typeof event.warningCount === 'number' ? { warningCount: event.warningCount } : {}),
          });
        }
        continue;
      }
      if (event.type === 'error') {
        const audit = isReviewAuditEvidence(event.audit) ? event.audit : undefined;
        throw new ReviewStreamError(
          typeof event.message === 'string' && event.message ? event.message : 'Review stream failed',
          typeof event.code === 'string' && event.code ? event.code : 'REVIEW_INFRA_ERROR',
          audit,
        );
      }
      if (event.type === 'done') {
        const result = parseReviewResultPayload(event.data);
        if (!result) {
          throw new ReviewStreamError('Review done event has an invalid payload');
        }
        return result;
      }
    }
    return null;
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    buffer = buffer.replace(/\r\n/g, '\n');
    let separator = buffer.indexOf('\n\n');
    while (separator !== -1) {
      const result = processFrame(buffer.slice(0, separator));
      buffer = buffer.slice(separator + 2);
      if (result) return result;
      separator = buffer.indexOf('\n\n');
    }
  }

  buffer += decoder.decode();
  buffer = buffer.replace(/\r\n/g, '\n').trim();
  if (buffer) {
    const result = processFrame(buffer);
    if (result) return result;
  }
  throw new ReviewStreamError('Review stream ended before a done event');
}

export function parseReviewResultPayload(value: unknown): ReviewStreamResult | null {
  if (!isRecord(value)) return null;
  if (!isReviewFinalStatus(value.status) || value.status === 'REVIEW_INFRA_ERROR') return null;
  if (typeof value.reviewId !== 'string' || !value.reviewId || typeof value.contractHash !== 'string' || !value.contractHash) return null;
  if (typeof value.passes !== 'boolean' || typeof value.fixedContent !== 'string') return null;
  if (!Array.isArray(value.issues) || !value.issues.every(isReviewIssue)) return null;
  if (!Array.isArray(value.reviewLog) || !value.reviewLog.every(isReviewLogEntry)) return null;
  if (!Array.isArray(value.changeLog) || !value.changeLog.every(isChangeLogEntry)) return null;
  if (!isReviewAuditEvidence(value.audit)) return null;

  const hasError = value.issues.some((issue) => issue.severity === 'ERROR');
  if (value.passes === hasError) return null;
  if (value.status === 'BLOCKED' && value.passes) return null;
  if ((value.status === 'PASSED' || value.status === 'PASSED_WITH_WARNINGS') && !value.passes) return null;
  const warningCount = value.issues.filter((issue) => issue.severity === 'WARN').length;
  if (value.status === 'PASSED' && warningCount > 0) return null;
  if (value.status === 'PASSED_WITH_WARNINGS' && warningCount === 0) return null;

  return {
    reviewId: value.reviewId,
    contractHash: value.contractHash,
    status: value.status,
    passes: value.passes,
    issues: value.issues,
    reviewLog: value.reviewLog,
    fixedContent: value.fixedContent,
    changeLog: value.changeLog,
    audit: value.audit,
    ...(typeof value.cacheHit === 'boolean' ? { cacheHit: value.cacheHit } : {}),
  };
}

function isReviewIssue(value: unknown): value is ReviewIssue {
  return isRecord(value)
    && (value.severity === 'ERROR' || value.severity === 'WARN')
    && nonBlankString(value.rule)
    && nonBlankString(value.message);
}

function isReviewLogEntry(value: unknown): value is ReviewLogEntry {
  return isRecord(value)
    && typeof value.round === 'number'
    && typeof value.action === 'string'
    && typeof value.errorCount === 'number'
    && typeof value.message === 'string';
}

function isChangeLogEntry(value: unknown): value is ChangeLogEntry {
  return isRecord(value)
    && typeof value.what === 'string'
    && typeof value.why === 'string'
    && typeof value.before === 'string'
    && typeof value.after === 'string';
}

function isReviewAuditEvidence(value: unknown): value is ReviewAuditEvidence {
  return isRecord(value)
    && nonBlankString(value.reviewId)
    && nonBlankString(value.rulesetVersion)
    && nonBlankString(value.startedAt)
    && nonBlankString(value.completedAt)
    && Array.isArray(value.rounds)
    && value.rounds.every((round) => isRecord(round)
      && typeof round.round === 'number'
      && typeof round.receivedAt === 'string'
      && typeof round.rawResponseLength === 'number'
      && typeof round.rawResponseSha256 === 'string'
      && (round.parseStatus === 'SUCCESS' || round.parseStatus === 'FAILED')
      && (round.schemaValidationStatus === 'SUCCESS' || round.schemaValidationStatus === 'FAILED')
      && typeof round.referenceSummaryAvailable === 'boolean'
      && (round.referenceSnapshotId == null || typeof round.referenceSnapshotId === 'string')
      && (round.contractSource == null || round.contractSource === 'EMBEDDED' || round.contractSource === 'INFERRED')
      && (round.contractSourceHash == null || typeof round.contractSourceHash === 'string')
      && (round.deterministicErrorCount == null || typeof round.deterministicErrorCount === 'number')
      && (round.deterministicWarningCount == null || typeof round.deterministicWarningCount === 'number')
      && (round.responseKind == null || round.responseKind === 'PRIMARY' || round.responseKind === 'SCHEMA_RECOVERY')
      && (round.diagnostic == null || typeof round.diagnostic === 'string'));
}

function isReviewFinalStatus(value: unknown): value is ReviewFinalStatus {
  return value === 'PASSED'
    || value === 'PASSED_WITH_WARNINGS'
    || value === 'BLOCKED'
    || value === 'REVIEW_INFRA_ERROR';
}

function isProgressStage(value: unknown): value is ReviewProgressStage {
  return value === 'preparing'
    || value === 'reviewing'
    || value === 'analyzing'
    || value === 'fixing'
    || value === 'complete';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
