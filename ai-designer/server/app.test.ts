import assert from 'node:assert/strict';
import test from 'node:test';

import { createApp } from './app';
import { REVIEW_RULESET_VERSION } from './llm/reviewProtocol';
import { compilePlanContract, hashPlanContent, hashPlanContract, upsertPlanContractBlock } from './llm/planContract';
import { invalidateReferenceSummaryCache } from './services/referenceSummary';
import { reviewStore } from './services/reviewStore';

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

test('review SSE exposes a persisted review id and fails closed when semantic review is unavailable', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/llm/review-plan`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ planContent: 'module: demo\nEntity: Demo\nDDL Entity VO Mapper Service Controller', stage: 'master', projectId: 'test-project', subjectId: 'test-master' }),
    });
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type') ?? '', /text\/event-stream/);
    assert.equal(response.headers.get('x-accel-buffering'), 'no');
    const reviewId = response.headers.get('x-review-id');
    assert.ok(reviewId);

    const events = (await response.text())
      .split('\n\n')
      .map((frame) => frame.split('\n').find((line) => line.startsWith('data:')))
      .filter((line): line is string => Boolean(line))
      .map((line) => JSON.parse(line.slice(5).trim()) as { type: string; stage?: string; round?: number; totalRounds?: number; code?: string });

    const progress = events.filter((event) => event.type === 'progress');
    assert.deepEqual(progress.map((event) => event.stage), ['preparing', 'reviewing', 'analyzing', 'complete']);
    assert.ok(progress.every((event) => event.round === 1 && event.totalRounds === 1));
    assert.equal(events.at(-1)?.type, 'error');
    assert.equal(events.at(-1)?.code, 'REVIEW_INFRA_ERROR');

    const persisted = await fetch(`${baseUrl}/api/llm/review-status/${reviewId}`);
    assert.equal(persisted.status, 200);
    const body = await persisted.json() as { data?: { reviewId?: string; status?: string; progress?: { phase?: string } } };
    assert.equal(body.data?.reviewId, reviewId);
    assert.equal(body.data?.status, 'REVIEW_INFRA_ERROR');
    assert.equal(body.data?.progress?.phase, 'COMPLETE');
  });
});


test('review rejects subjects without persistent project and object identity', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/llm/review-plan`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ planContent: '# Plan', stage: 'master' }),
    });
    assert.equal(response.status, 400);
  });
});


test('split endpoint cannot be called without a current persisted review credential', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/llm/split-plan`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        planContent: '# Unreviewed plan', projectId: 'split-project', subjectId: 'split-master',
      }),
    });
    assert.equal(response.status, 428);
    const body = await response.json() as { code?: string };
    assert.equal(body.code, 'REVIEW_REQUIRED');
  });
});

test('sub-plan review requires a successful parent review and inherits its reference context', async () => {
  const source = 'module: demo\nEntity: Demo\nDDL Entity VO Mapper Service Controller';
  const content = upsertPlanContractBlock(source, compilePlanContract(source).contract);
  const contract = compilePlanContract(content).contract;
  const contractHash = hashPlanContract(contract);
  const projectId = `sub-review-context-${Date.now()}`;
  const parentReviewId = `master-review-${Date.now()}`;
  const subjectId = 'sub_1';
  const descriptor = {
    id: subjectId,
    index: 1,
    title: 'Demo delivery',
    prerequisites: [],
    deliverableIds: contract.deliverables.slice(0, 1).map((item) => item.id),
    contractHash,
    referencedElementIds: [],
    inputTypes: [],
    outputTypes: [],
  };

  await reviewStore.save({
    reviewId: parentReviewId,
    projectId,
    subjectId: 'master_1',
    stage: 'master',
    status: 'PASSED',
    contentHash: hashPlanContent(content),
    contractHash,
    contract,
    referenceSnapshotId: 'master-reference-snapshot',
    rulesetVersion: REVIEW_RULESET_VERSION,
    issues: [],
    audit: {
      reviewId: parentReviewId,
      rulesetVersion: REVIEW_RULESET_VERSION,
      startedAt: new Date().toISOString(),
      completedAt: new Date().toISOString(),
      rounds: [],
    },
    startedAt: new Date().toISOString(),
    completedAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  });

  const previousPartBUrl = process.env.PART_B_URL;
  let adaptationRequests = 0;
  let searchRequests = 0;
  const referenceServer = (await import('node:http')).createServer((request, response) => {
    if (request.url === '/api/project/adaptation-summary') {
      adaptationRequests += 1;
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ data: 'BladeX reference adaptation summary' }));
      return;
    }
    if (request.url === '/api/project/reference/search') searchRequests += 1;
    response.writeHead(404).end();
  }).listen(0, '127.0.0.1');
  await new Promise<void>((resolve) => referenceServer.once('listening', resolve));
  const referenceAddress = referenceServer.address();
  if (!referenceAddress || typeof referenceAddress === 'string') throw new Error('No reference server address');
  process.env.PART_B_URL = `http://127.0.0.1:${referenceAddress.port}`;
  invalidateReferenceSummaryCache();

  try {
    await withServer(async (baseUrl) => {
      const missingParent = await fetch(`${baseUrl}/api/llm/review-plan`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          planContent: content,
          stage: 'subplan',
          projectId,
          subjectId,
          parentReviewId: 'missing-parent-review',
          subjectDescriptor: descriptor,
        }),
      });
      assert.equal(missingParent.status, 428);
      assert.equal((await missingParent.json() as { code?: string }).code, 'REVIEW_REQUIRED');

      const response = await fetch(`${baseUrl}/api/llm/review-plan`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          planContent: content,
          stage: 'subplan',
          projectId,
          subjectId,
          parentReviewId,
          subjectDescriptor: descriptor,
        }),
      });
      assert.equal(response.status, 200);
      await response.text();
    });

    const childReview = reviewStore.list(projectId).find((record) => record.stage === 'subplan' && record.subjectId === subjectId);
    assert.ok(childReview);
    assert.equal(childReview.referenceSnapshotId, 'master-reference-snapshot');
    assert.equal(adaptationRequests, 1);
    assert.equal(searchRequests, 0);
  } finally {
    invalidateReferenceSummaryCache();
    if (previousPartBUrl === undefined) delete process.env.PART_B_URL;
    else process.env.PART_B_URL = previousPartBUrl;
    await new Promise<void>((resolve, reject) => referenceServer.close((error) => error ? reject(error) : resolve()));
  }
});
