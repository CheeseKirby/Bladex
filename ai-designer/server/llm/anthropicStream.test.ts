import assert from 'node:assert/strict';
import { test } from 'node:test';

import { consumeAnthropicStream } from './anthropicStream';

function streamFromText(parts: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const part of parts) controller.enqueue(encoder.encode(part));
      controller.close();
    },
  });
}

test('收到 message_stop 后返回累计字符数且不重复完成', async () => {
  const chunks: string[] = [];
  const stream = streamFromText([
    'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}\n\n',
    'data: {"type":"message_stop"}\n\n',
  ]);

  const totalChars = await consumeAnthropicStream(stream.getReader(), (chunk) => chunks.push(chunk));

  assert.equal(totalChars, 5);
  assert.deepEqual(chunks, ['hello']);
});

test('上游在 message_stop 前结束时拒绝，不能伪装成 complete', async () => {
  const stream = streamFromText([
    'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"partial"}}\n\n',
  ]);

  await assert.rejects(
    consumeAnthropicStream(stream.getReader(), () => undefined),
    /message_stop/,
  );
});

test('支持 CRLF 分隔和跨 chunk SSE 帧', async () => {
  const chunks: string[] = [];
  const stream = streamFromText([
    'data: {"type":"content_block_delta","delta":{"type":"text_delta",',
    '"text":"ok"}}\r\n\r\ndata: {"type":"message_stop"}\r\n\r\n',
  ]);

  const totalChars = await consumeAnthropicStream(stream.getReader(), (chunk) => chunks.push(chunk));

  assert.equal(totalChars, 2);
  assert.deepEqual(chunks, ['ok']);
});
