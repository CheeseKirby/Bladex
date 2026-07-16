import assert from 'node:assert/strict';
import { describe, test, afterEach } from 'node:test';

import { generatePlanStream } from './api';
import type { SSEMessage } from '../types/plan';

// generatePlanStream 用全局 fetch + ReadableStream,这里 mock fetch 注入 SSE 帧,
// 验证回调编排(尤其 P1.1: error 帧不进 onMessage, 只走 onError)。

const originalFetch = globalThis.fetch;

function sseStream(frames: string[]): ReadableStream<Uint8Array> {
  const enc = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const f of frames) controller.enqueue(enc.encode(f));
      controller.close();
    },
  });
}

function mockFetchOk(frames: string[]) {
  globalThis.fetch = (async () => ({
    ok: true,
    status: 200,
    body: sseStream(frames),
  })) as unknown as typeof globalThis.fetch;
}

function mockFetchHttpError(status: number, body: string) {
  globalThis.fetch = (async () => ({
    ok: false,
    status,
    text: async () => body,
  })) as unknown as typeof globalThis.fetch;
}

interface RunResult {
  messages: SSEMessage[];
  errMsg: string | null;
  completed: boolean;
}

function runStream(frames: string[]): Promise<RunResult> {
  mockFetchOk(frames);
  const messages: SSEMessage[] = [];
  let errMsg: string | null = null;
  let completed = false;
  return new Promise<RunResult>((resolve) => {
    generatePlanStream(
      {} as never,
      (m) => messages.push(m),
      (e) => { errMsg = e.message; resolve({ messages, errMsg, completed }); },
      () => { completed = true; resolve({ messages, errMsg, completed }); },
    );
  });
}

describe('generatePlanStream', () => {
  afterEach(() => { globalThis.fetch = originalFetch; });

  test('正常流: content 与 complete 都进 onMessage, 并触发 onComplete', async () => {
    const { messages, errMsg, completed } = await runStream([
      'data: {"type":"content","chunk":"hello"}\n\n',
      'data: {"type":"complete","tokensUsed":5}\n\n',
    ]);
    assert.equal(errMsg, null);
    assert.equal(completed, true);
    assert.equal(messages.length, 2, 'content + complete 都进 onMessage');
    assert.equal(messages[0].type, 'content');
    assert.equal(messages[1].type, 'complete');
  });

  test('error 帧不进 onMessage, 只触发 onError (P1.1 修复)', async () => {
    const { messages, errMsg, completed } = await runStream([
      'data: {"type":"content","chunk":"x"}\n\n',
      'data: {"type":"error","error":"boom"}\n\n',
    ]);
    assert.equal(completed, false, 'error 流不应触发 onComplete');
    assert.equal(errMsg, 'boom');
    assert.equal(messages.length, 1, '只 content 进 onMessage');
    assert.equal(messages[0].type, 'content');
    assert.ok(!messages.find((m) => m.type === 'error'), 'error 帧绝不能进 onMessage');
  });

  test('progress 帧进 onMessage, 随后 complete 触发 onComplete', async () => {
    const { messages, completed } = await runStream([
      'data: {"type":"progress","stage":"analyzing","message":"..."}\n\n',
      'data: {"type":"complete","tokensUsed":0}\n\n',
    ]);
    assert.equal(completed, true);
    assert.equal(messages[0].type, 'progress');
    assert.equal(messages[1].type, 'complete');
  });

  test('HTTP 非 2xx -> onError, 不读流', async () => {
    mockFetchHttpError(502, 'upstream dead');
    const messages: SSEMessage[] = [];
    let errMsg: string | null = null;
    let completed = false;
    await new Promise<void>((resolve) => {
      generatePlanStream(
        {} as never,
        (m) => messages.push(m),
        (e) => { errMsg = e.message; resolve(); },
        () => { completed = true; resolve(); },
      );
    });
    assert.equal(completed, false);
    assert.ok((errMsg ?? '').includes('502'));
    assert.equal(messages.length, 0);
  });
});

  test('流在 complete 帧前结束 -> onError, 不应静默当作完成', async () => {
    const { messages, errMsg, completed } = await runStream([
      'data: {"type":"content","chunk":"partial"}\n\n',
    ]);
    assert.equal(completed, false);
    assert.equal(messages.length, 1);
    assert.match(errMsg ?? '', /complete/);
  });

  test('complete 回调不被 reader.cancel 的未决 Promise 阻塞', async () => {
    const encoder = new TextEncoder();
    let readCount = 0;
    let cancelCalled = false;
    globalThis.fetch = (async () => ({
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: async () => {
            if (readCount++ === 0) {
              return { done: false, value: encoder.encode('data: {"type":"complete","tokensUsed":1}\n\n') };
            }
            return { done: true, value: undefined };
          },
          cancel: () => {
            cancelCalled = true;
            return new Promise<void>(() => undefined);
          },
        }),
      },
    })) as unknown as typeof globalThis.fetch;

    let completed = false;
    await Promise.race([
      new Promise<void>((resolve) => {
        generatePlanStream(
          {} as never,
          () => undefined,
          (error) => { throw error; },
          () => { completed = true; resolve(); },
        );
      }),
      new Promise<void>((_, reject) => setTimeout(() => reject(new Error('onComplete was blocked')), 50)),
    ]);

    assert.equal(completed, true);
    assert.equal(cancelCalled, true);
  });

  test('error 回调抛异常也只触发一次终态', async () => {
    mockFetchOk(['data: {"type":"error","error":"boom"}\n\n']);
    let errorCalls = 0;

    generatePlanStream(
      {} as never,
      () => undefined,
      () => {
        errorCalls += 1;
        throw new Error('consumer callback failed');
      },
      () => undefined,
    );

    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(errorCalls, 1);
  });
