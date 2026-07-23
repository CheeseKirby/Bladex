import assert from 'node:assert/strict';
import test from 'node:test';

import { awaitWithHeartbeat } from './llm';

test('review wait heartbeat repeats while pending and stops after completion', async () => {
  let heartbeats = 0;
  let resolveOperation: ((value: string) => void) | undefined;
  const operation = new Promise<string>((resolve) => { resolveOperation = resolve; });
  const pending = awaitWithHeartbeat(operation, () => { heartbeats += 1; }, 10);

  const deadline = Date.now() + 500;
  while (heartbeats < 2 && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.ok(heartbeats >= 2);

  resolveOperation?.('done');
  assert.equal(await pending, 'done');
  const completedCount = heartbeats;
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.equal(heartbeats, completedCount);
});
