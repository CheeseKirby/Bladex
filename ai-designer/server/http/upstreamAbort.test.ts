import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { test } from 'node:test';

import { bindUpstreamAbort } from './upstreamAbort';

test('response 在上游响应头到达前关闭也会取消上游请求', () => {
  const responseEvents = new EventEmitter();
  const controller = new AbortController();
  const cleanup = bindUpstreamAbort(responseEvents, controller, 60_000);

  responseEvents.emit('close');

  assert.equal(controller.signal.aborted, true);
  cleanup();
});

test('正常完成后清理监听器，不再因 close 误取消', () => {
  const responseEvents = new EventEmitter();
  const controller = new AbortController();
  const cleanup = bindUpstreamAbort(responseEvents, controller, 60_000);

  cleanup();
  responseEvents.emit('close');

  assert.equal(controller.signal.aborted, false);
});

test('达到总超时会取消上游请求', async () => {
  const responseEvents = new EventEmitter();
  const controller = new AbortController();
  const cleanup = bindUpstreamAbort(responseEvents, controller, 5);

  await new Promise((resolve) => setTimeout(resolve, 20));

  assert.equal(controller.signal.aborted, true);
  cleanup();
});
