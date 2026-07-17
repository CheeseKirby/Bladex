import assert from 'node:assert/strict';
import test from 'node:test';
import { fetchWithTransientRetry } from './fetchRetry';

test('retries transient fetch failures before returning response', async () => {
  let calls = 0;
  const mock: typeof fetch = async () => {
    calls += 1;
    if (calls < 3) throw new TypeError('fetch failed');
    return new Response('ok', { status: 200 });
  };
  const response = await fetchWithTransientRetry('https://example.test', {}, mock, 2);
  assert.equal(response.status, 200);
  assert.equal(calls, 3);
});

test('does not retry HTTP responses', async () => {
  let calls = 0;
  const mock: typeof fetch = async () => { calls += 1; return new Response('bad', { status: 503 }); };
  const response = await fetchWithTransientRetry('https://example.test', {}, mock, 2);
  assert.equal(response.status, 503);
  assert.equal(calls, 1);
});
