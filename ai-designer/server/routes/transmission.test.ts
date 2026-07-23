import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { hashPlanContent, hashPlanContract, hashSubPlanDescriptor, upsertPlanContractBlock, type PlanContract } from '../llm/planContract';
import { signPlanBundle } from '../llm/bundleSignature';
import { reviewStore, type ReviewRecord } from '../services/reviewStore';
import { prepareV2PlanBundle } from './transmission';

const contract = JSON.parse(readFileSync('../contracts/fixtures/canonical-plan-contract-v2.json', 'utf8')) as PlanContract;
const masterContent = upsertPlanContractBlock('# Fixture master plan', contract);
const subPlanContent = upsertPlanContractBlock('Reviewed sub-plan', contract);
const contractHash = hashPlanContract(contract);
const now = new Date().toISOString();
const signingSecret = 'fixture-bundle-secret';

function record(overrides: Partial<ReviewRecord> & Pick<ReviewRecord, 'reviewId' | 'subjectId' | 'stage' | 'contentHash'>): ReviewRecord {
  return {
    projectId: 'bundle-project',
    status: 'PASSED',
    contractHash,
    contract,
    referenceSnapshotId: contract.referenceSnapshotId,
    rulesetVersion: contract.rulesetVersion,
    issues: [],
    audit: { reviewId: overrides.reviewId, rulesetVersion: contract.rulesetVersion, startedAt: now, completedAt: now, rounds: [] },
    startedAt: now,
    completedAt: now,
    updatedAt: now,
    ...overrides,
  };
}

async function seedReviews(suffix: string) {
  const masterReviewId = `master-review-${suffix}`;
  const subReviewId = `sub-review-${suffix}`;
  await reviewStore.save(record({ reviewId: masterReviewId, subjectId: `master-${suffix}`, stage: 'master', contentHash: hashPlanContent(masterContent) }));
  await reviewStore.save(record({
    reviewId: subReviewId, subjectId: `sub-${suffix}`, stage: 'subplan', contentHash: hashPlanContent(subPlanContent),
    subjectDescriptorHash: hashSubPlanDescriptor({
      id: `sub-${suffix}`, index: 1, title: 'Canonical work', contentHash: hashPlanContent(subPlanContent),
      prerequisites: [], deliverableIds: contract.deliverables.map((item) => item.id), contractHash,
      referencedElementIds: [], inputTypes: [], outputTypes: [],
    }),
  }));
  return { masterReviewId, subReviewId };
}

function bundle(suffix: string, ids: string[], reviews: { masterReviewId: string; subReviewId: string }) {
  return {
    projectId: 'bundle-project',
    projectName: 'Bundle project',
    masterPlan: { id: `master-${suffix}`, version: 1, content: masterContent },
    subPlans: [{
      id: `sub-${suffix}`, index: 1, title: 'Canonical work', content: subPlanContent, prerequisites: [],
      deliverableIds: ids, contractHash,
    }],
    generationIdentity: contract.identity,
    metadata: { sourceService: 'ai-designer', generatedBy: 'test', transmittedAt: now },
    reviewManifest: {
      masterReviewId: reviews.masterReviewId,
      subPlanReviews: [{ subPlanId: `sub-${suffix}`, reviewId: reviews.subReviewId }],
    },
  };
}

test('reviewed v2 bundle is enriched with canonical contract, manifest hashes and bundle hash', async () => {
  const suffix = `${Date.now()}-ok`;
  const reviews = await seedReviews(suffix);
  const result = prepareV2PlanBundle(bundle(suffix, contract.deliverables.map((item) => item.id), reviews), signingSecret);
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.deepEqual(result.value.canonicalContract, contract);
  assert.equal(typeof result.value.bundleHash, 'string');
  assert.equal(typeof result.value.bundleSignature, 'string');
  assert.equal(result.value.writeTarget, 'ISOLATED');
  assert.deepEqual(result.value.generationIdentity, contract.identity);
  const manifest = result.value.reviewManifest as Record<string, unknown>;
  assert.equal(manifest.masterContentHash, hashPlanContent(masterContent));
  assert.equal(manifest.contractHash, contractHash);
  assert.equal(result.value.bundleSignature, signPlanBundle(String(result.value.bundleHash), manifest as any, signingSecret));
});

test('bundle preparation rejects duplicate ownership and review replay across projects', async () => {
  const suffix = `${Date.now()}-blocked`;
  const reviews = await seedReviews(suffix);
  const duplicateId = contract.deliverables[0].id;
  const duplicate = prepareV2PlanBundle(bundle(suffix, [duplicateId, duplicateId], reviews), signingSecret);
  assert.equal(duplicate.ok, false);
  if (!duplicate.ok) assert.equal(duplicate.code, 'DUPLICATE_DELIVERABLE_OWNER');

  const replay = bundle(suffix, contract.deliverables.map((item) => item.id), reviews);
  replay.projectId = 'another-project';
  const replayResult = prepareV2PlanBundle(replay, signingSecret);
  assert.equal(replayResult.ok, false);
  if (!replayResult.ok) assert.equal(replayResult.code, 'REVIEW_REQUIRED');
});


test('v2 transmission fails closed when the shared signing secret is unavailable', async () => {
  const suffix = `${Date.now()}-no-secret`;
  const reviews = await seedReviews(suffix);
  const result = prepareV2PlanBundle(bundle(suffix, contract.deliverables.map((item) => item.id), reviews), '');
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.code, 'BUNDLE_SIGNING_UNAVAILABLE');
});


test('superseded review credentials and mixed review snapshots are rejected', async () => {
  const suffix = `${Date.now()}-superseded`;
  const reviews = await seedReviews(suffix);
  await reviewStore.save(record({
    reviewId: `master-review-new-${suffix}`, subjectId: `master-${suffix}`, stage: 'master',
    status: 'BLOCKED', contentHash: hashPlanContent(masterContent),
    startedAt: new Date(Date.now() + 1000).toISOString(), completedAt: new Date(Date.now() + 1001).toISOString(),
  }));
  const stale = prepareV2PlanBundle(bundle(suffix, contract.deliverables.map((item) => item.id), reviews), signingSecret);
  assert.equal(stale.ok, false);
  if (!stale.ok) assert.equal(stale.code, 'REVIEW_REQUIRED');

  const mismatchSuffix = `${Date.now()}-snapshot`;
  const mismatchReviews = await seedReviews(mismatchSuffix);
  await reviewStore.save(record({
    reviewId: mismatchReviews.subReviewId, subjectId: `sub-${mismatchSuffix}`, stage: 'subplan',
    contentHash: hashPlanContent(subPlanContent), referenceSnapshotId: 'another-snapshot',
  }));
  const mismatch = prepareV2PlanBundle(
    bundle(mismatchSuffix, contract.deliverables.map((item) => item.id), mismatchReviews), signingSecret,
  );
  assert.equal(mismatch.ok, false);
  if (!mismatch.ok) assert.equal(mismatch.code, 'REVIEW_CONTEXT_MISMATCH');
});


test('sub-plan descriptor and embedded contract mutations invalidate review evidence', async () => {
  const suffix = `${Date.now()}-descriptor`;
  const reviews = await seedReviews(suffix);
  const ids = contract.deliverables.map((item) => item.id);

  const titleChanged = bundle(suffix, ids, reviews);
  titleChanged.subPlans[0].title = 'Changed after review';
  const descriptorResult = prepareV2PlanBundle(titleChanged, signingSecret);
  assert.equal(descriptorResult.ok, false);
  if (!descriptorResult.ok) assert.equal(descriptorResult.code, 'REVIEW_STALE');

  const contractChanged = bundle(suffix, ids, reviews);
  const changedContract = structuredClone(contract);
  changedContract.identity = { ...changedContract.identity, moduleName: 'othermodule' };
  contractChanged.subPlans[0].content = upsertPlanContractBlock('Reviewed sub-plan', changedContract);
  const contractResult = prepareV2PlanBundle(contractChanged, signingSecret);
  assert.equal(contractResult.ok, false);
  if (!contractResult.ok) assert.equal(contractResult.code, 'CONTRACT_STALE');
});
