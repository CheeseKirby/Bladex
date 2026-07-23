import assert from 'node:assert/strict';
import { test } from 'node:test';
import { consumeReviewResponseWithRecovery, mergeReviewProgressEvent, waitForPersistedReview } from './reviewStatus';
import { ReviewStreamError, type ReviewStreamResult } from './reviewStream';

function result(overrides: Partial<ReviewStreamResult> = {}): ReviewStreamResult {
  return {
    reviewId: 'review-1',
    contractHash: 'contract-1',
    status: 'PASSED',
    passes: true,
    issues: [],
    reviewLog: [],
    fixedContent: 'reviewed plan',
    changeLog: [],
    audit: {
      reviewId: 'review-1', rulesetVersion: 'review-v1',
      startedAt: '2026-07-22T00:00:00.000Z', completedAt: '2026-07-22T00:00:01.000Z', rounds: [],
    },
    ...overrides,
  };
}

function persisted(reviewResult: ReviewStreamResult | undefined, status = reviewResult?.status ?? 'IN_PROGRESS') {
  return new Response(JSON.stringify({ success: true, data: {
    reviewId: 'review-1', projectId: 'project-1', subjectId: 'master-1', stage: 'master', status,
    contractHash: 'contract-1', rulesetVersion: 'review-v1', issues: reviewResult?.issues ?? [],
    progress: { phase: reviewResult ? 'COMPLETE' : 'SEMANTIC_REVIEW', message: reviewResult ? 'done' : 'running',
      lastHeartbeatAt: '2026-07-22T00:00:00.500Z', sequence: reviewResult ? 2 : 1 },
    startedAt: '2026-07-22T00:00:00.000Z', updatedAt: '2026-07-22T00:00:01.000Z',
    ...(reviewResult ? { completedAt: '2026-07-22T00:00:01.000Z', result: reviewResult } : {}),
  } }), { status: 200, headers: { 'content-type': 'application/json' } });
}

function sseResponse(frames: object[], reviewId = 'review-1'): Response {
  const body = frames.map((frame) => `data: ${JSON.stringify(frame)}\n\n`).join('');
  return new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream', 'x-review-id': reviewId } });
}

test('review status recovery client', async (t) => {
  const originalFetch = globalThis.fetch;
  try {
    await t.test('returns a valid SSE result and reports the review id', async () => {
      const expected = result();
      let capturedId = '';
      const actual = await consumeReviewResponseWithRecovery(
        sseResponse([{ type: 'done', data: expected }]), undefined, () => undefined,
        (reviewId) => { capturedId = reviewId; },
      );
      assert.equal(actual.reviewId, expected.reviewId);
      assert.equal(capturedId, expected.reviewId);
    });

    await t.test('recovers from an SSE disconnect through persisted polling', async () => {
      globalThis.fetch = (async () => persisted(result())) as typeof fetch;
      const progress: string[] = [];
      const actual = await consumeReviewResponseWithRecovery(
        sseResponse([{ type: 'progress', stage: 'reviewing', message: 'working' }]),
        undefined,
        (event) => progress.push(event.message),
      );
      assert.equal(actual.status, 'PASSED');
      assert.ok(progress.includes('working'));
    });

    await t.test('a duplicate POST attaches to the existing review id', async () => {
      globalThis.fetch = (async () => persisted(result())) as typeof fetch;
      let capturedId = '';
      const response = new Response(JSON.stringify({ code: 'REVIEW_IN_PROGRESS', reviewId: 'review-1' }), {
        status: 409, headers: { 'content-type': 'application/json', 'x-review-id': 'review-1' },
      });
      const actual = await consumeReviewResponseWithRecovery(response, undefined, () => undefined,
        (reviewId) => { capturedId = reviewId; });
      assert.equal(actual.reviewId, 'review-1');
      assert.equal(capturedId, 'review-1');
    });

    await t.test('transient REVIEW_NOT_FOUND is retried before the final result appears', async () => {
      let calls = 0;
      globalThis.fetch = (async () => {
        calls += 1;
        if (calls === 1) return new Response(JSON.stringify({ success: false, code: 'REVIEW_NOT_FOUND', error: 'not yet' }), { status: 404 });
        return persisted(result());
      }) as typeof fetch;
      const actual = await waitForPersistedReview('review-1', undefined, () => undefined, () => true, 1);
      assert.equal(actual.status, 'PASSED');
      assert.equal(calls, 2);
    });

    await t.test('persisted infrastructure failures surface their stored rule and message', async () => {
      globalThis.fetch = (async () => new Response(JSON.stringify({ success: true, data: {
        reviewId: 'review-1', projectId: 'project-1', subjectId: 'master-1', stage: 'master', status: 'REVIEW_INFRA_ERROR',
        contractHash: 'contract-1', rulesetVersion: 'review-v1',
        issues: [{ severity: 'ERROR', rule: 'REVIEW-INTERRUPTED', message: 'restart interrupted review' }],
        progress: { phase: 'COMPLETE', message: 'failed', lastHeartbeatAt: '2026-07-22T00:00:01.000Z', sequence: 2 },
        startedAt: '2026-07-22T00:00:00.000Z', completedAt: '2026-07-22T00:00:01.000Z', updatedAt: '2026-07-22T00:00:01.000Z',
      } }), { status: 200 })) as typeof fetch;
      await assert.rejects(
        waitForPersistedReview('review-1', undefined, () => undefined, () => true, 1),
        (error: unknown) => error instanceof ReviewStreamError
          && error.code === 'REVIEW-INTERRUPTED'
          && /restart interrupted/.test(error.message),
      );
    });


    await t.test('deduplicates equivalent SSE and persisted progress while preserving one-round metadata', () => {
      const first = mergeReviewProgressEvent([], {
        stage: 'reviewing', message: 'Semantic review is still processing...', round: 1, totalRounds: 1,
      });
      const second = mergeReviewProgressEvent(first, {
        stage: 'reviewing', message: 'Semantic review is still processing...', round: 1, totalRounds: 1, sequence: 4,
      });
      assert.equal(second.length, 1);
      assert.equal(second[0].totalRounds, 1);
    });

    await t.test('abort stops polling promptly', async () => {
      globalThis.fetch = (async () => persisted(undefined)) as typeof fetch;
      const controller = new AbortController();
      const pending = waitForPersistedReview('review-1', controller.signal, () => undefined, () => true, 10_000);
      setTimeout(() => controller.abort(new DOMException('cancelled', 'AbortError')), 5);
      await assert.rejects(pending, (error: unknown) => error instanceof Error && error.name === 'AbortError');
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});
