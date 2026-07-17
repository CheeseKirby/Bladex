import assert from 'node:assert/strict';
import { test } from 'node:test';
import { getReferenceAdaptationSummary, invalidateReferenceSummaryCache } from './referenceSummary';

test('reference summary caches successful responses and supports invalidation', async () => {
  invalidateReferenceSummaryCache();
  let calls = 0;
  const fetchMock = (async () => {
    calls += 1;
    return new Response(JSON.stringify({ data: `summary-${calls}` }), { status: 200 });
  }) as typeof fetch;

  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), 'summary-1');
  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), 'summary-1');
  assert.equal(calls, 1);

  invalidateReferenceSummaryCache();
  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), 'summary-2');
  assert.equal(calls, 2);
});

test('reference summary negative responses are cached briefly', async () => {
  invalidateReferenceSummaryCache();
  let calls = 0;
  const fetchMock = (async () => {
    calls += 1;
    return new Response('', { status: 503 });
  }) as typeof fetch;

  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), null);
  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), null);
  assert.equal(calls, 1);
});
