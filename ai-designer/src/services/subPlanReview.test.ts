import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { SubPlan } from '../types/plan';
import type { ReviewStreamResult } from './reviewStream';
import {
  getSubPlanReviewReadiness,
  reviewSubPlanWithRecovery,
  runLimitedConcurrency,
} from './subPlanReview';

function subPlan(overrides: Partial<SubPlan> = {}): SubPlan {
  return {
    id: 'sub_1',
    masterPlanId: 'master_1',
    index: 1,
    title: 'Entity',
    planContent: 'draft',
    prerequisites: [],
    deliverableIds: ['d.entity'],
    contractHash: 'contract-1',
    status: 'GENERATED',
    ...overrides,
  };
}

function result(overrides: Partial<ReviewStreamResult> = {}): ReviewStreamResult {
  return {
    reviewId: 'review-1',
    contractHash: 'contract-1',
    status: 'PASSED',
    passes: true,
    issues: [],
    reviewLog: [],
    fixedContent: 'reviewed',
    changeLog: [],
    audit: {
      reviewId: 'review-1',
      rulesetVersion: 'review-v3',
      startedAt: '2026-07-22T00:00:00.000Z',
      completedAt: '2026-07-22T00:01:00.000Z',
      rounds: [],
    },
    ...overrides,
  };
}

test('review recovery automatically retries one repaired BLOCKED draft', async () => {
  const requests: Array<{ parentReviewId: string; content: string; contractHash: string }> = [];
  const reviewer = async (request: { parentReviewId: string; content: string; contractHash: string }) => {
    requests.push({ parentReviewId: request.parentReviewId, content: request.content, contractHash: request.contractHash });
    if (requests.length === 1) {
      return result({
        reviewId: 'blocked-1',
        status: 'BLOCKED',
        passes: false,
        issues: [{ severity: 'ERROR', rule: 'FIXABLE', message: 'retry repaired content' }],
        fixedContent: 'repaired draft',
        contractHash: 'contract-1',
        changeLog: [{ what: 'repair', why: 'mechanical', before: 'draft', after: 'repaired draft' }],
      });
    }
    return result({ reviewId: 'passed-2', fixedContent: 'repaired draft' });
  };

  const outcome = await reviewSubPlanWithRecovery('project-1', 'master-review-1', subPlan(), undefined, () => undefined, reviewer);
  assert.equal(outcome.attempts, 2);
  assert.equal(outcome.result.reviewId, 'passed-2');
  assert.deepEqual(requests, [
    { parentReviewId: 'master-review-1', content: 'draft', contractHash: 'contract-1' },
    { parentReviewId: 'master-review-1', content: 'repaired draft', contractHash: 'contract-1' },
  ]);
});

test('review recovery does not loop when a BLOCKED review made no repair', async () => {
  let calls = 0;
  const blocked = result({
    status: 'BLOCKED', passes: false,
    issues: [{ severity: 'ERROR', rule: 'ARCHITECTURE', message: 'manual decision required' }],
    fixedContent: 'draft',
  });
  const outcome = await reviewSubPlanWithRecovery('project-1', 'master-review-1', subPlan(), undefined, () => undefined, async () => {
    calls += 1;
    return blocked;
  });
  assert.equal(calls, 1);
  assert.equal(outcome.attempts, 1);
  assert.equal(outcome.result.status, 'BLOCKED');
});

test('limited concurrency isolates failures and never exceeds the configured limit', async () => {
  let active = 0;
  let maxActive = 0;
  const settled: string[] = [];
  const results = await runLimitedConcurrency([1, 2, 3, 4, 5], 2, async (value) => {
    active += 1;
    maxActive = Math.max(maxActive, active);
    await new Promise((resolve) => setTimeout(resolve, 5));
    active -= 1;
    if (value === 3) throw new Error('blocked');
    return value * 10;
  }, (value) => settled.push(value.status));

  assert.equal(maxActive, 2);
  assert.equal(results.length, 5);
  assert.equal(results[2].status, 'rejected');
  assert.equal(results.filter((item) => item.status === 'fulfilled').length, 4);
  assert.equal(settled.length, 5);
});

test('transmission readiness requires a successful current review for every sub-plan', () => {
  const plans = [
    subPlan({ id: 'sub_1', reviewedContent: 'reviewed', reviewId: 'r1', reviewStatus: 'PASSED', status: 'REVIEWED' }),
    subPlan({ id: 'sub_2', index: 2, reviewId: undefined, reviewStatus: undefined }),
    subPlan({ id: 'sub_3', index: 3, reviewedContent: 'reviewed', reviewId: 'r3', reviewStatus: 'PASSED', contractHash: 'stale' }),
  ];
  const readiness = getSubPlanReviewReadiness(plans, 'contract-1');
  assert.equal(readiness.reviewed, 1);
  assert.equal(readiness.pending, 2);
  assert.equal(readiness.canTransmit, false);
  assert.deepEqual(readiness.pendingIds, ['sub_2', 'sub_3']);
});
