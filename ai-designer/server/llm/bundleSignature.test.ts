import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { signPlanBundle, type BundleReviewManifest } from './bundleSignature';

test('shared reviewed bundle fixture keeps the cross-language HMAC signature', () => {
  const bundleHash = readFileSync('../contracts/fixtures/canonical-plan-bundle-v2.sha256', 'utf8').trim();
  const manifest = JSON.parse(readFileSync('../contracts/fixtures/canonical-plan-review-manifest-v2.json', 'utf8')) as BundleReviewManifest;
  const expected = readFileSync('../contracts/fixtures/canonical-plan-bundle-v2.hmac-sha256', 'utf8').trim();
  assert.equal(signPlanBundle(bundleHash, manifest, 'fixture-bundle-secret'), expected);
});

test('bundle signature is stable across sub-plan evidence order', () => {
  const manifest: BundleReviewManifest = {
    masterReviewId: 'master', masterContentHash: 'content', contractHash: 'contract',
    rulesetVersion: 'rules', referenceSnapshotId: 'snapshot',
    subPlanReviews: [
      { subPlanId: 'b', reviewId: 'review-b', contentHash: 'hash-b' },
      { subPlanId: 'a', reviewId: 'review-a', contentHash: 'hash-a' },
    ],
  };
  const reversed = { ...manifest, subPlanReviews: [...manifest.subPlanReviews].reverse() };
  assert.equal(signPlanBundle('bundle', manifest, 'secret'), signPlanBundle('bundle', reversed, 'secret'));
});
