import assert from 'node:assert/strict';
import { test } from 'node:test';

import { consumeReviewStream } from './reviewStream';

function reviewStream(frames: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const frame of frames) controller.enqueue(encoder.encode(frame));
      controller.close();
    },
  });
}

test('review error 帧向外抛出，不能被当作解析错误吞掉', async () => {
  const stream = reviewStream(['data: {"type":"error","message":"review timeout"}\n\n']);
  await assert.rejects(consumeReviewStream(stream.getReader(), () => undefined), /review timeout/);
});

test('没有 done 帧就结束时拒绝，不能标记 REVIEWED', async () => {
  const stream = reviewStream(['data: {"type":"progress","message":"round 1"}\n\n']);
  await assert.rejects(consumeReviewStream(stream.getReader(), () => undefined), /done/);
});

test('done 帧返回审查结果并转发进度', async () => {
  const progress: string[] = [];
  const stream = reviewStream([
    'data: {"type":"progress","message":"round 1"}\n\n',
    'data: {"type":"done","data":{"passes":true,"issues":[],"fixedContent":"fixed","reviewLog":[],"changeLog":[]}}\n\n',
  ]);

  const result = await consumeReviewStream(stream.getReader(), (message) => progress.push(message));

  assert.equal(result.passes, true);
  assert.equal(result.fixedContent, 'fixed');
  assert.deepEqual(progress, ['round 1']);
});
