import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { compilePlanContract, hashPlanContract } from '../llm/planContract';
import { ReviewStore, type ReviewRecord } from './reviewStore';

function record(status: 'IN_PROGRESS' | 'PASSED' | 'BLOCKED' = 'PASSED', overrides: Partial<ReviewRecord> = {}): ReviewRecord {
  return { ...recordBase(status), ...overrides };
}

function recordBase(status: 'IN_PROGRESS' | 'PASSED' | 'BLOCKED' = 'PASSED'): ReviewRecord {
  const contract = compilePlanContract('moduleName: demo\nentityName: Demo\ntableName: blade_demo\nEntity: Demo\nDDL Entity Mapper Service Controller').contract;
  return {
    reviewId: 'review-1', projectId: 'project-1', subjectId: 'master-1', stage: 'master' as const,
    status, contentHash: contract.sourceHash, contractHash: hashPlanContract(contract), contract,
    rulesetVersion: 'review-v3-contract-deterministic', issues: [],
    audit: { reviewId: 'review-1', rulesetVersion: 'review-v3-contract-deterministic', startedAt: '2026-07-21T00:00:00.000Z', completedAt: '2026-07-21T00:00:01.000Z', rounds: [] },
    startedAt: '2026-07-21T00:00:00.000Z', completedAt: status === 'PASSED' ? '2026-07-21T00:00:01.000Z' : undefined,
    updatedAt: '2026-07-21T00:00:00.000Z',
  };
}

test('review records survive store restart', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'review-store-'));
  try {
    const file = path.join(dir, 'reviews.json');
    const first = new ReviewStore(file);
    await first.save(record());
    const second = new ReviewStore(file);
    assert.equal(second.get('review-1')?.status, 'PASSED');
    assert.match(await readFile(file, 'utf8'), /review-1/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('in-progress records are recovered as explicit infrastructure failures', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'review-store-'));
  try {
    const store = new ReviewStore(path.join(dir, 'reviews.json'));
    await store.save(record('IN_PROGRESS'));
    assert.equal(await store.recoverInterrupted(), 1);
    assert.equal(store.get('review-1')?.status, 'REVIEW_INFRA_ERROR');
  } finally { await rm(dir, { recursive: true, force: true }); }
});


test('a newer review supersedes an older pass only for the same bound subject', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'review-store-'));
  try {
    const store = new ReviewStore(path.join(dir, 'reviews.json'));
    await store.save(record('PASSED', { reviewId: 'review-r1' }));
    await store.save(record('PASSED', {
      reviewId: 'review-other', subjectId: 'master-other',
      startedAt: '2026-07-21T00:00:00.500Z', updatedAt: '2026-07-21T00:00:00.500Z',
    }));
    await store.save(record('IN_PROGRESS', {
      reviewId: 'review-r2', startedAt: '2026-07-21T00:00:02.000Z',
      completedAt: undefined, updatedAt: '2026-07-21T00:00:02.000Z',
    }));

    assert.equal(store.get('review-r1')?.status, 'SUPERSEDED');
    assert.equal(store.isCurrent('review-r1'), false);
    assert.equal(store.isCurrent('review-r2'), true);
    assert.equal(store.get('review-other')?.status, 'PASSED');
    assert.equal(store.isCurrent('review-other'), true);

    await store.save(record('BLOCKED', {
      reviewId: 'review-r2', startedAt: '2026-07-21T00:00:02.000Z',
      completedAt: '2026-07-21T00:00:03.000Z', updatedAt: '2026-07-21T00:00:03.000Z',
    }));
    assert.equal(store.get('review-r1')?.status, 'SUPERSEDED');
    assert.equal(store.get('review-r2')?.status, 'BLOCKED');
  } finally { await rm(dir, { recursive: true, force: true }); }
});


test('failed persistence rolls back both the new review and superseded status changes', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'review-store-rollback-'));
  const storeDirectory = path.join(dir, 'store');
  try {
    const store = new ReviewStore(path.join(storeDirectory, 'reviews.json'));
    await store.save(record('PASSED', { reviewId: 'rollback-r1' }));
    await rm(storeDirectory, { recursive: true, force: true });
    await writeFile(storeDirectory, 'blocks mkdir', 'utf8');

    await assert.rejects(store.save(record('IN_PROGRESS', {
      reviewId: 'rollback-r2', startedAt: '2026-07-21T00:00:02.000Z', completedAt: undefined,
    })));
    assert.equal(store.get('rollback-r1')?.status, 'PASSED');
    assert.equal(store.get('rollback-r2'), undefined);
    assert.equal(store.isCurrent('rollback-r1'), true);
  } finally { await rm(dir, { recursive: true, force: true }); }
});


test('begin atomically deduplicates identical in-progress reviews', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'review-store-begin-'));
  try {
    const store = new ReviewStore(path.join(dir, 'reviews.json'));
    const first = record('IN_PROGRESS', { reviewId: 'begin-r1', completedAt: undefined });
    const second = record('IN_PROGRESS', { reviewId: 'begin-r2', completedAt: undefined });
    const [left, right] = await Promise.all([store.begin(first), store.begin(second)]);
    assert.equal(left.created, true);
    assert.equal(right.created, false);
    assert.equal(right.record.reviewId, left.record.reviewId);
    assert.equal(store.list('project-1').filter((item) => item.status === 'IN_PROGRESS').length, 1);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('a superseded successful review remains reusable by immutable input hashes', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'review-store-reuse-'));
  try {
    const store = new ReviewStore(path.join(dir, 'reviews.json'));
    const successful = record('PASSED', {
      reviewId: 'reuse-r1',
      referenceSnapshotId: 'snapshot-1',
      result: {
        finalStatus: 'PASSED',
        passes: true,
        fixedContent: 'reviewed content',
        reviewLog: [],
        changeLog: [],
      },
    });
    await store.save(successful);
    await store.begin(record('IN_PROGRESS', {
      reviewId: 'reuse-r2',
      referenceSnapshotId: undefined,
      completedAt: undefined,
      startedAt: '2026-07-22T00:00:02.000Z',
    }));

    assert.equal(store.get('reuse-r1')?.status, 'SUPERSEDED');
    const reusable = store.findReusable({
      projectId: successful.projectId,
      subjectId: successful.subjectId,
      stage: successful.stage,
      contentHash: successful.contentHash,
      contractHash: successful.contractHash,
      subjectDescriptorHash: successful.subjectDescriptorHash,
      rulesetVersion: successful.rulesetVersion,
      referenceSnapshotId: 'snapshot-1',
      excludeReviewId: 'reuse-r2',
    });
    assert.equal(reusable?.reviewId, 'reuse-r1');
    assert.equal(reusable?.result?.finalStatus, 'PASSED');
    assert.equal(store.latest('project-1', 'master-1', 'master')?.reviewId, 'reuse-r2');
  } finally { await rm(dir, { recursive: true, force: true }); }
});
