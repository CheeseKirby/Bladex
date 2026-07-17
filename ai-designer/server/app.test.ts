import assert from 'node:assert/strict';
import test from 'node:test';

import { createApp } from './app';

async function withServer(run: (baseUrl: string) => Promise<void>): Promise<void> {
  const server = createApp().listen(0, '127.0.0.1');
  await new Promise<void>((resolve) => server.once('listening', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('No TCP address');
  try {
    await run(`http://127.0.0.1:${address.port}`);
  } finally {
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

test('health endpoint remains available without privileged headers', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/health`);
    assert.equal(response.status, 200);
    assert.equal((await response.json() as { status: string }).status, 'ok');
  });
});

test('LLM endpoint rejects structurally oversized payloads before mock or live execution', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/llm/enrich-requirement`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ userInput: 'x'.repeat(500_001) }),
    });
    assert.equal(response.status, 413);
  });
});


test('JSON parser preserves HTTP 413 for transport-level body limits', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/llm/enrich-requirement`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ userInput: 'x'.repeat(2_100_000) }),
    });
    assert.equal(response.status, 413);
    assert.equal((await response.json() as { msg: string }).msg, 'Request body too large');
  });
});

test('review SSE emits structured stage progress before the final result', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/llm/review-plan`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ planContent: '# Plan', stage: 'master' }),
    });
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type') ?? '', /text\/event-stream/);
    assert.equal(response.headers.get('x-accel-buffering'), 'no');

    const events = (await response.text())
      .split('\n\n')
      .map((frame) => frame.split('\n').find((line) => line.startsWith('data:')))
      .filter((line): line is string => Boolean(line))
      .map((line) => JSON.parse(line.slice(5).trim()) as { type: string; stage?: string; round?: number; totalRounds?: number });

    const progress = events.filter((event) => event.type === 'progress');
    assert.deepEqual(progress.map((event) => event.stage), ['preparing', 'reviewing', 'analyzing', 'complete']);
    assert.ok(progress.every((event) => event.round === 1 && event.totalRounds === 4));
    assert.equal(events.at(-1)?.type, 'done');
  });
});
