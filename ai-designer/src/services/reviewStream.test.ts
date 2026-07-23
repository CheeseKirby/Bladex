import assert from 'node:assert/strict';
import { test } from 'node:test';

import { consumeReviewStream, ReviewStreamError } from './reviewStream';

function reviewStream(frames: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const frame of frames) controller.enqueue(encoder.encode(frame));
      controller.close();
    },
  });
}

const audit = {
  reviewId: 'review-1',
  rulesetVersion: 'review-v2-fail-closed',
  startedAt: '2026-07-21T00:00:00.000Z',
  completedAt: '2026-07-21T00:00:01.000Z',
  rounds: [{
    round: 1,
    receivedAt: '2026-07-21T00:00:01.000Z',
    rawResponseLength: 42,
    rawResponseSha256: 'a'.repeat(64),
    parseStatus: 'SUCCESS',
    schemaValidationStatus: 'SUCCESS',
    referenceSummaryAvailable: true,
  }],
};

test('review error frame throws a typed infrastructure error with audit evidence', async () => {
  const stream = reviewStream([`data: ${JSON.stringify({ type: 'error', code: 'REVIEW_INFRA_ERROR', message: 'review timeout', audit })}\n\n`]);
  await assert.rejects(
    consumeReviewStream(stream.getReader(), () => undefined),
    (error: unknown) => error instanceof ReviewStreamError
      && error.code === 'REVIEW_INFRA_ERROR'
      && error.audit?.reviewId === 'review-1',
  );
});

test('stream ending without done is rejected and cannot mark the plan reviewed', async () => {
  const stream = reviewStream(['data: {"type":"progress","message":"round 1"}\n\n']);
  await assert.rejects(consumeReviewStream(stream.getReader(), () => undefined), /done/);
});

test('done frame returns strictly validated review result and forwards progress', async () => {
  const progress: unknown[] = [];
  const stream = reviewStream([
    'data: {"type":"progress","message":"round 1"}\n\n',
    `data: ${JSON.stringify({ type: 'done', data: { reviewId: 'review-1', contractHash: 'b'.repeat(64), status: 'PASSED', passes: true, issues: [], fixedContent: 'fixed', reviewLog: [], changeLog: [], audit } })}\n\n`,
  ]);

  const result = await consumeReviewStream(stream.getReader(), (message) => progress.push(message));

  assert.equal(result.status, 'PASSED');
  assert.equal(result.passes, true);
  assert.equal(result.fixedContent, 'fixed');
  assert.equal(result.audit.reviewId, 'review-1');
  assert.deepEqual(progress, [{ message: 'round 1' }]);
});

test('structured progress preserves stage, round and error count', async () => {
  const progress: unknown[] = [];
  const stream = reviewStream([
    'data: {"type":"progress","stage":"analyzing","round":1,"totalRounds":4,"errorCount":2,"message":"found errors"}\n\n',
    `data: ${JSON.stringify({ type: 'done', data: { reviewId: 'review-1', contractHash: 'b'.repeat(64), status: 'BLOCKED', passes: false, issues: [{ severity: 'ERROR', rule: 'X', message: 'broken' }], fixedContent: 'fixed', reviewLog: [], changeLog: [], audit } })}\n\n`,
  ]);

  await consumeReviewStream(stream.getReader(), (event) => progress.push(event));

  assert.deepEqual(progress, [{
    stage: 'analyzing',
    round: 1,
    totalRounds: 4,
    errorCount: 2,
    message: 'found errors',
  }]);
});

test('invalid done payload and pass/error contradictions fail closed', async () => {
  const missingAudit = reviewStream([
    'data: {"type":"done","data":{"status":"PASSED","passes":true,"issues":[],"fixedContent":"fixed","reviewLog":[],"changeLog":[]}}\n\n',
  ]);
  await assert.rejects(consumeReviewStream(missingAudit.getReader(), () => undefined), /invalid payload/);

  const contradictory = reviewStream([
    `data: ${JSON.stringify({ type: 'done', data: { reviewId: 'review-1', contractHash: 'b'.repeat(64), status: 'PASSED', passes: true, issues: [{ severity: 'ERROR', rule: 'X', message: 'broken' }], fixedContent: 'fixed', reviewLog: [], changeLog: [], audit } })}\n\n`,
  ]);
  await assert.rejects(consumeReviewStream(contradictory.getReader(), () => undefined), /invalid payload/);

  const warningStatusMismatch = reviewStream([
    `data: ${JSON.stringify({ type: 'done', data: { reviewId: 'review-1', contractHash: 'b'.repeat(64), status: 'PASSED', passes: true, issues: [{ severity: 'WARN', rule: 'W', message: 'warning' }], fixedContent: 'fixed', reviewLog: [], changeLog: [], audit } })}\n\n`,
  ]);
  await assert.rejects(consumeReviewStream(warningStatusMismatch.getReader(), () => undefined), /invalid payload/);
});
