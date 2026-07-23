import { createHmac } from 'node:crypto';

export interface BundleReviewManifest {
  masterReviewId: string;
  masterContentHash: string;
  contractHash: string;
  rulesetVersion: string;
  referenceSnapshotId?: string;
  subPlanReviews: Array<{
    subPlanId: string;
    reviewId: string;
    contentHash: string;
  }>;
}

export interface PlanBundleSignatureMaterial {
  bundleHash: string;
  masterReviewId: string;
  masterContentHash: string;
  contractHash: string;
  rulesetVersion: string;
  referenceSnapshotId: string;
  subPlanReviews: Array<{
    subPlanId: string;
    reviewId: string;
    contentHash: string;
  }>;
}

/**
 * Canonical material authenticated between Part A and Part B.
 * The sub-plan evidence is sorted so UI order cannot change the signature.
 */
export function planBundleSignatureMaterial(
  bundleHash: string,
  manifest: BundleReviewManifest,
): PlanBundleSignatureMaterial {
  return {
    bundleHash,
    masterReviewId: manifest.masterReviewId,
    masterContentHash: manifest.masterContentHash,
    contractHash: manifest.contractHash,
    rulesetVersion: manifest.rulesetVersion,
    referenceSnapshotId: manifest.referenceSnapshotId ?? '',
    subPlanReviews: manifest.subPlanReviews
      .map((review) => ({ ...review }))
      .sort((left, right) => compareCodeUnits(left.subPlanId, right.subPlanId)
        || compareCodeUnits(left.reviewId, right.reviewId)
        || compareCodeUnits(left.contentHash, right.contentHash)),
  };
}

/** HMAC-SHA256 credential proving that the reviewed bundle was issued by trusted Part A. */
export function signPlanBundle(
  bundleHash: string,
  manifest: BundleReviewManifest,
  secret: string,
): string {
  const normalizedSecret = secret.trim();
  if (!normalizedSecret) throw new Error('Plan bundle signing secret is required.');
  return createHmac('sha256', normalizedSecret)
    .update(stableStringify(planBundleSignatureMaterial(bundleHash, manifest)), 'utf8')
    .digest('hex');
}

function compareCodeUnits(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

function stableStringify(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (isRecord(value)) {
    return `{${Object.keys(value).filter((key) => value[key] !== undefined).sort()
      .map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
