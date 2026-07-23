import type { SubPlan } from '../types/plan';
import {
  ReviewStreamError,
  type ReviewProgressEvent,
  type ReviewStreamResult,
} from './reviewStream';
import { consumeReviewResponseWithRecovery } from './reviewStatus';

export interface SubPlanReviewRequest {
  projectId: string;
  parentReviewId: string;
  subjectId: string;
  content: string;
  contractHash: string;
  descriptor: {
    id: string;
    index: number;
    title: string;
    prerequisites: string[];
    deliverableIds: string[];
    contractHash: string;
    referencedElementIds: string[];
    inputTypes: string[];
    outputTypes: string[];
  };
}

export type SubPlanReviewer = (
  request: SubPlanReviewRequest,
  signal: AbortSignal | undefined,
  onProgress: (event: ReviewProgressEvent) => void,
) => Promise<ReviewStreamResult>;

export interface SubPlanReviewOutcome {
  result: ReviewStreamResult;
  attempts: number;
}

export interface SubPlanReviewReadiness {
  total: number;
  reviewed: number;
  pending: number;
  pendingIds: string[];
  canTransmit: boolean;
}

export async function requestSubPlanReview(
  request: SubPlanReviewRequest,
  signal?: AbortSignal,
  onProgress: (event: ReviewProgressEvent) => void = () => undefined,
): Promise<ReviewStreamResult> {
  const response = await fetch('/api/llm/review-plan', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      planContent: request.content,
      stage: 'subplan',
      projectId: request.projectId,
      parentReviewId: request.parentReviewId,
      subjectId: request.subjectId,
      subjectDescriptor: request.descriptor,
    }),
    signal,
  });
  return consumeReviewResponseWithRecovery(response, signal, onProgress);
}

export async function reviewSubPlanWithRecovery(
  projectId: string,
  parentReviewId: string,
  subPlan: SubPlan,
  signal?: AbortSignal,
  onProgress: (event: ReviewProgressEvent) => void = () => undefined,
  reviewer: SubPlanReviewer = requestSubPlanReview,
  maxAttempts = 2,
): Promise<SubPlanReviewOutcome> {
  let content = subPlan.reviewedContent || subPlan.planContent;
  let contractHash = subPlan.contractHash || '';
  let lastResult: ReviewStreamResult | undefined;
  const boundedAttempts = Math.max(1, Math.min(2, Math.trunc(maxAttempts)));

  for (let attempt = 1; attempt <= boundedAttempts; attempt += 1) {
    const request: SubPlanReviewRequest = {
      projectId,
      parentReviewId,
      subjectId: subPlan.id,
      content,
      contractHash,
      descriptor: {
        id: subPlan.id,
        index: subPlan.index,
        title: subPlan.title,
        prerequisites: subPlan.prerequisites,
        deliverableIds: subPlan.deliverableIds ?? [],
        contractHash,
        referencedElementIds: subPlan.referencedElementIds ?? [],
        inputTypes: subPlan.inputTypes ?? [],
        outputTypes: subPlan.outputTypes ?? [],
      },
    };
    const result = await reviewer(request, signal, onProgress);
    lastResult = result;
    if (isSuccessfulReviewResult(result)) return { result, attempts: attempt };

    const repaired = result.fixedContent.trim();
    const contentChanged = repaired.length > 0 && repaired !== content;
    const hasAppliedChanges = result.changeLog.length > 0;
    if (attempt >= boundedAttempts || !contentChanged || !hasAppliedChanges) {
      return { result, attempts: attempt };
    }
    content = result.fixedContent;
    contractHash = result.contractHash;
    onProgress({
      stage: 'fixing',
      message: `Applied review repairs; automatically retrying (${attempt + 1}/${boundedAttempts})...`,
      round: attempt + 1,
      totalRounds: boundedAttempts,
    });
  }
  if (!lastResult) throw new ReviewStreamError('Sub-plan review produced no result');
  return { result: lastResult, attempts: boundedAttempts };
}

export function isSuccessfulReviewResult(result: ReviewStreamResult): boolean {
  return (result.status === 'PASSED' || result.status === 'PASSED_WITH_WARNINGS')
    && result.passes
    && !result.issues.some((issue) => issue.severity === 'ERROR');
}

export function isTransferableSubPlan(subPlan: SubPlan, masterContractHash?: string): boolean {
  return Boolean(
    subPlan.reviewId?.trim()
    && (subPlan.reviewStatus === 'PASSED' || subPlan.reviewStatus === 'PASSED_WITH_WARNINGS')
    && subPlan.reviewedContent?.trim()
    && subPlan.contractHash?.trim()
    && masterContractHash?.trim()
    && subPlan.contractHash === masterContractHash,
  );
}

export function getSubPlanReviewReadiness(
  subPlans: SubPlan[],
  masterContractHash?: string,
): SubPlanReviewReadiness {
  const pendingIds = subPlans.filter((item) => !isTransferableSubPlan(item, masterContractHash)).map((item) => item.id);
  return {
    total: subPlans.length,
    reviewed: subPlans.length - pendingIds.length,
    pending: pendingIds.length,
    pendingIds,
    canTransmit: subPlans.length > 0 && pendingIds.length === 0,
  };
}

export async function runLimitedConcurrency<T, R>(
  items: readonly T[],
  concurrency: number,
  worker: (item: T, index: number) => Promise<R>,
  onSettled?: (result: PromiseSettledResult<R>, item: T, index: number) => void,
): Promise<Array<PromiseSettledResult<R>>> {
  const results: Array<PromiseSettledResult<R>> = new Array(items.length);
  const limit = Math.max(1, Math.min(items.length || 1, Math.trunc(concurrency) || 1));
  let cursor = 0;

  const runWorker = async () => {
    while (true) {
      const index = cursor;
      cursor += 1;
      if (index >= items.length) return;
      const item = items[index];
      let settled: PromiseSettledResult<R>;
      try {
        settled = { status: 'fulfilled', value: await worker(item, index) };
      } catch (reason) {
        settled = { status: 'rejected', reason };
      }
      results[index] = settled;
      onSettled?.(settled, item, index);
    }
  };

  await Promise.all(Array.from({ length: limit }, () => runWorker()));
  return results;
}
