import {
  consumeReviewStream,
  parseReviewResultPayload,
  ReviewStreamError,
  type ReviewProgressEvent,
  type ReviewStreamResult,
} from './reviewStream';
import type { ReviewFinalStatus } from '../types/plan';

export type ReviewPhase = 'QUEUED' | 'REFERENCE_EVIDENCE' | 'DETERMINISTIC_VALIDATION' | 'SEMANTIC_REVIEW' | 'FINALIZING' | 'COMPLETE';
export type PersistedReviewState = ReviewFinalStatus | 'IN_PROGRESS' | 'SUPERSEDED';

export interface ReviewProgressSnapshot {
  phase: ReviewPhase;
  message: string;
  lastHeartbeatAt: string;
  sequence: number;
  errorCount?: number;
  warningCount?: number;
}

export interface PersistedReviewStatus {
  reviewId: string;
  projectId: string;
  subjectId: string;
  stage: 'master' | 'subplan';
  status: PersistedReviewState;
  contractHash: string;
  referenceSnapshotId?: string;
  rulesetVersion: string;
  progress?: ReviewProgressSnapshot;
  issues: Array<{ severity: 'ERROR' | 'WARN'; rule: string; message: string }>;
  startedAt: string;
  completedAt?: string;
  updatedAt: string;
  result?: ReviewStreamResult;
}

export async function fetchReviewStatus(reviewId: string, signal?: AbortSignal): Promise<PersistedReviewStatus> {
  return requestReviewStatus(`/api/llm/review-status/${encodeURIComponent(reviewId)}`, signal);
}

export async function fetchLatestReviewStatus(
  projectId: string,
  subjectId: string,
  stage: 'master' | 'subplan',
  signal?: AbortSignal,
): Promise<PersistedReviewStatus> {
  const query = new URLSearchParams({ projectId, subjectId, stage });
  return requestReviewStatus(`/api/llm/review-status?${query.toString()}`, signal);
}

export async function waitForPersistedReview(
  reviewId: string,
  signal: AbortSignal | undefined,
  onProgress: (event: ReviewProgressEvent) => void,
  shouldContinue: () => boolean = () => true,
  intervalMs = 2_000,
): Promise<ReviewStreamResult> {
  let lastSequence = -1;
  let consecutiveStatusFailures = 0;
  while (shouldContinue()) {
    throwIfAborted(signal);
    let status: PersistedReviewStatus;
    try {
      status = await fetchReviewStatus(reviewId, signal);
      consecutiveStatusFailures = 0;
    } catch (error) {
      throwIfAborted(signal);
      if (!isRetryableStatusError(error) || consecutiveStatusFailures >= 5) throw error;
      consecutiveStatusFailures += 1;
      if (consecutiveStatusFailures === 1) {
        onProgress({
          stage: 'reviewing',
          message: '\u5ba1\u6838\u4ecd\u5728\u540e\u53f0\u6267\u884c\uff0c\u72b6\u6001\u8fde\u63a5\u6682\u65f6\u4e2d\u65ad\uff0c\u6b63\u5728\u81ea\u52a8\u91cd\u8fde...',
        });
      }
      await delay(intervalMs, signal);
      continue;
    }
    if (status.progress && status.progress.sequence !== lastSequence) {
      lastSequence = status.progress.sequence;
      onProgress(progressEvent(status.progress));
    }
    if (status.status === 'SUPERSEDED') {
      throw new ReviewStreamError(`Persisted review ${reviewId} was superseded by a newer review`, 'REVIEW_SUPERSEDED');
    }
    if (status.result) return status.result;
    if (status.status === 'REVIEW_INFRA_ERROR') {
      const issue = status.issues.find((item) => item.severity === 'ERROR');
      throw new ReviewStreamError(issue?.message || 'Review infrastructure failed', issue?.rule || 'REVIEW_INFRA_ERROR');
    }
    if (status.status !== 'IN_PROGRESS') {
      throw new ReviewStreamError(`Persisted review ${reviewId} completed without a recoverable result`, 'REVIEW_RESULT_UNAVAILABLE');
    }
    await delay(intervalMs, signal);
  }
  throw new ReviewStreamError('Review status polling was superseded', 'REVIEW_POLL_SUPERSEDED');
}

export async function consumeReviewResponseWithRecovery(
  response: Response,
  signal: AbortSignal | undefined,
  onProgress: (event: ReviewProgressEvent) => void,
  onReviewId: (reviewId: string) => void = () => undefined,
): Promise<ReviewStreamResult> {
  const headerReviewId = response.headers.get('x-review-id')?.trim();
  if (headerReviewId) onReviewId(headerReviewId);
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as Record<string, unknown> | null;
    const duplicateReviewId = typeof payload?.reviewId === 'string' ? payload.reviewId : undefined;
    if (response.status === 409 && payload?.code === 'REVIEW_IN_PROGRESS' && duplicateReviewId) {
      onReviewId(duplicateReviewId);
      onProgress({ stage: 'reviewing', message: '\u68c0\u6d4b\u5230\u76f8\u540c\u5ba1\u6838\u6b63\u5728\u6267\u884c\uff0c\u5df2\u8fde\u63a5\u5230\u73b0\u6709\u5ba1\u6838\u4efb\u52a1\u3002' });
      return waitForPersistedReview(duplicateReviewId, signal, onProgress);
    }
    throw new ReviewStreamError(
      typeof payload?.error === 'string' ? payload.error : `HTTP ${response.status}`,
      typeof payload?.code === 'string' ? payload.code : 'REVIEW_INFRA_ERROR',
    );
  }
  if (!response.body) {
    if (headerReviewId) return waitForPersistedReview(headerReviewId, signal, onProgress);
    throw new ReviewStreamError('Review response did not contain a stream or review id');
  }

  const reader = response.body.getReader();
  const pollingController = new AbortController();
  const forwardAbort = () => pollingController.abort(signal?.reason);
  if (signal?.aborted) forwardAbort();
  else signal?.addEventListener('abort', forwardAbort, { once: true });

  type Outcome = { source: 'stream' | 'poll'; result?: ReviewStreamResult; error?: unknown };
  let streamTask: Promise<Outcome> | undefined = consumeReviewStream(reader, onProgress)
    .then((result) => ({ source: 'stream' as const, result }), (error) => ({ source: 'stream' as const, error }));
  let pollingTask: Promise<Outcome> | undefined = headerReviewId
    ? waitForPersistedReview(headerReviewId, pollingController.signal, onProgress)
      .then((result) => ({ source: 'poll' as const, result }), (error) => ({ source: 'poll' as const, error }))
    : undefined;
  let streamError: unknown;
  let pollingError: unknown;

  try {
    while (streamTask || pollingTask) {
      const pending = [streamTask, pollingTask].filter((task): task is Promise<Outcome> => Boolean(task));
      const outcome = await Promise.race(pending);
      if (outcome.result) return outcome.result;
      if (outcome.source === 'stream') {
        streamError = outcome.error;
        streamTask = undefined;
      } else {
        pollingError = outcome.error;
        pollingTask = undefined;
      }
    }
    throw pollingError ?? streamError ?? new ReviewStreamError('Review did not produce a recoverable result');
  } finally {
    pollingController.abort(new DOMException('Review result settled', 'AbortError'));
    signal?.removeEventListener('abort', forwardAbort);
    void reader.cancel().catch(() => undefined);
  }
}

function progressEvent(progress: ReviewProgressSnapshot): ReviewProgressEvent {
  const stage = progress.phase === 'REFERENCE_EVIDENCE' || progress.phase === 'QUEUED' ? 'preparing'
    : progress.phase === 'DETERMINISTIC_VALIDATION' ? 'fixing'
      : progress.phase === 'SEMANTIC_REVIEW' ? 'reviewing'
        : progress.phase === 'FINALIZING' ? 'analyzing' : 'complete';
  return {
    stage,
    message: progress.message,
    errorCount: progress.errorCount,
    warningCount: progress.warningCount,
    round: 1,
    totalRounds: 1,
    sequence: progress.sequence,
  };
}

export function mergeReviewProgressEvent(
  events: ReviewProgressEvent[],
  event: ReviewProgressEvent,
  limit = 16,
): ReviewProgressEvent[] {
  const key = reviewProgressEventKey(event);
  if (events.some((candidate) => reviewProgressEventKey(candidate) === key)) return events;
  return [...events, event].slice(-limit);
}

function reviewProgressEventKey(event: ReviewProgressEvent): string {
  // SSE progress does not carry the persisted sequence while polling does. A semantic key therefore
  // deduplicates the same heartbeat regardless of which recovery channel delivered it first.
  return [
    event.stage ?? '', event.message, event.round ?? '', event.totalRounds ?? '',
    event.errorCount ?? '', event.warningCount ?? '',
  ].join('|');
}

async function requestReviewStatus(url: string, signal?: AbortSignal): Promise<PersistedReviewStatus> {
  const response = await fetch(url, { signal });
  const payload = await response.json().catch(() => null) as { success?: unknown; data?: unknown; error?: unknown; code?: unknown } | null;
  if (!response.ok || payload?.success !== true || !isRecord(payload.data)) {
    throw new ReviewStreamError(
      typeof payload?.error === 'string' ? payload.error : `HTTP ${response.status}`,
      typeof payload?.code === 'string' ? payload.code : 'REVIEW_STATUS_ERROR',
    );
  }
  const data = payload.data;
  if (typeof data.reviewId !== 'string' || typeof data.projectId !== 'string' || typeof data.subjectId !== 'string'
    || (data.stage !== 'master' && data.stage !== 'subplan') || !isPersistedReviewState(data.status)
    || typeof data.contractHash !== 'string' || typeof data.rulesetVersion !== 'string'
    || typeof data.startedAt !== 'string' || typeof data.updatedAt !== 'string'
    || !Array.isArray(data.issues) || !data.issues.every(isPersistedIssue)
    || (data.progress != null && !isProgressSnapshot(data.progress))) {
    throw new ReviewStreamError('Review status response has an invalid shape', 'REVIEW_STATUS_INVALID');
  }
  const result = data.result == null ? undefined : parseReviewResultPayload(data.result);
  if (data.result != null && !result) throw new ReviewStreamError('Persisted review result has an invalid shape', 'REVIEW_STATUS_INVALID');
  return { ...(data as unknown as PersistedReviewStatus), ...(result ? { result } : {}) };
}

function isRetryableStatusError(error: unknown): boolean {
  if (!(error instanceof ReviewStreamError)) return error instanceof TypeError;
  return error.code === 'REVIEW_NOT_FOUND' || error.code === 'REVIEW_STATUS_ERROR';
}

function isPersistedReviewState(value: unknown): value is PersistedReviewState {
  return value === 'IN_PROGRESS' || value === 'SUPERSEDED'
    || value === 'PASSED' || value === 'PASSED_WITH_WARNINGS'
    || value === 'BLOCKED' || value === 'REVIEW_INFRA_ERROR';
}

function isProgressSnapshot(value: unknown): value is ReviewProgressSnapshot {
  return isRecord(value)
    && (value.phase === 'QUEUED' || value.phase === 'REFERENCE_EVIDENCE'
      || value.phase === 'DETERMINISTIC_VALIDATION' || value.phase === 'SEMANTIC_REVIEW'
      || value.phase === 'FINALIZING' || value.phase === 'COMPLETE')
    && typeof value.message === 'string'
    && typeof value.lastHeartbeatAt === 'string'
    && typeof value.sequence === 'number'
    && (value.errorCount == null || typeof value.errorCount === 'number')
    && (value.warningCount == null || typeof value.warningCount === 'number');
}

function isPersistedIssue(value: unknown): value is PersistedReviewStatus['issues'][number] {
  return isRecord(value)
    && (value.severity === 'ERROR' || value.severity === 'WARN')
    && typeof value.rule === 'string'
    && typeof value.message === 'string';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw signal.reason instanceof Error ? signal.reason : new DOMException('Aborted', 'AbortError');
}

function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const cleanup = () => signal?.removeEventListener('abort', abort);
    const timer = setTimeout(() => {
      cleanup();
      resolve();
    }, ms);
    const abort = () => {
      clearTimeout(timer);
      cleanup();
      reject(signal?.reason instanceof Error ? signal.reason : new DOMException('Aborted', 'AbortError'));
    };
    if (signal?.aborted) abort();
    else signal?.addEventListener('abort', abort, { once: true });
  });
}
