import { mkdir, rename, unlink, writeFile } from 'node:fs/promises';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import type { ReviewAuditEvidence, ReviewFinalStatus } from '../llm/reviewProtocol';
import type { ReviewChangeLogPayload } from '../llm/reviewProtocol';
import type { DeterministicPlanIssue, PlanContract } from '../llm/planContract';

export type ReviewRecordStatus = ReviewFinalStatus | 'IN_PROGRESS' | 'SUPERSEDED';
export type ReviewPhase = 'QUEUED' | 'REFERENCE_EVIDENCE' | 'DETERMINISTIC_VALIDATION' | 'SEMANTIC_REVIEW' | 'FINALIZING' | 'COMPLETE';

export interface ReviewProgressState {
  phase: ReviewPhase;
  message: string;
  lastHeartbeatAt: string;
  sequence: number;
  errorCount?: number;
  warningCount?: number;
}

export interface StoredReviewResult {
  finalStatus?: ReviewFinalStatus;
  passes: boolean;
  fixedContent: string;
  reviewLog: Array<{ round: number; action: string; errorCount: number; message: string }>;
  changeLog: ReviewChangeLogPayload[];
  cacheHit?: boolean;
}

export interface ReviewRecord {
  reviewId: string;
  projectId: string;
  subjectId: string;
  stage: 'master' | 'subplan';
  status: ReviewRecordStatus;
  contentHash: string;
  contractHash: string;
  subjectDescriptorHash?: string;
  contract: PlanContract;
  referenceSnapshotId?: string;
  rulesetVersion: string;
  issues: DeterministicPlanIssue[];
  audit: ReviewAuditEvidence;
  progress?: ReviewProgressState;
  result?: StoredReviewResult;
  startedAt: string;
  completedAt?: string;
  updatedAt: string;
}

type ReviewStoreDocument = { schemaVersion: 1; records: ReviewRecord[] };

export function resolveReviewStorePath(configured = process.env.BFF_REVIEW_STORE_PATH): string {
  if (configured?.trim()) return path.resolve(configured.trim());
  return path.resolve(process.cwd(), '..', 'output', 'bff-reviews.json');
}

export class ReviewStore {
  private readonly records = new Map<string, ReviewRecord>();
  private writeQueue: Promise<void> = Promise.resolve();

  constructor(public readonly filePath = resolveReviewStorePath()) {
    this.load();
  }

  get(reviewId: string): ReviewRecord | undefined {
    const record = this.records.get(reviewId);
    return record ? structuredClone(record) : undefined;
  }

  list(projectId?: string): ReviewRecord[] {
    return Array.from(this.records.values())
      .filter((record) => !projectId || record.projectId === projectId)
      .map((record) => structuredClone(record))
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt));
  }


  latest(projectId: string, subjectId: string, stage: 'master' | 'subplan'): ReviewRecord | undefined {
    const candidates = this.list(projectId)
      .filter((record) => record.subjectId === subjectId && record.stage === stage);
    return candidates.find((record) => record.status !== 'SUPERSEDED') ?? candidates[0];
  }

  async begin(record: ReviewRecord): Promise<{ created: boolean; record: ReviewRecord }> {
    return this.enqueue(async () => {
      const existing = Array.from(this.records.values())
        .filter((candidate) => candidate.status === 'IN_PROGRESS'
          && candidate.projectId === record.projectId
          && candidate.subjectId === record.subjectId
          && candidate.stage === record.stage
          && candidate.contentHash === record.contentHash
          && candidate.contractHash === record.contractHash
          && candidate.subjectDescriptorHash === record.subjectDescriptorHash
          && candidate.rulesetVersion === record.rulesetVersion)
        .sort((left, right) => right.startedAt.localeCompare(left.startedAt))[0];
      if (existing) return { created: false, record: structuredClone(existing) };
      return { created: true, record: await this.saveUnlocked(record) };
    });
  }

  findReusable(criteria: {
    projectId: string;
    subjectId: string;
    stage: 'master' | 'subplan';
    contentHash: string;
    contractHash: string;
    subjectDescriptorHash?: string;
    rulesetVersion: string;
    referenceSnapshotId?: string;
    excludeReviewId?: string;
  }): ReviewRecord | undefined {
    return this.list(criteria.projectId).find((record) => record.reviewId !== criteria.excludeReviewId
      && record.subjectId === criteria.subjectId && record.stage === criteria.stage
      && record.contentHash === criteria.contentHash && record.contractHash === criteria.contractHash
      && record.subjectDescriptorHash === criteria.subjectDescriptorHash
      && record.rulesetVersion === criteria.rulesetVersion
      && record.referenceSnapshotId === criteria.referenceSnapshotId
      && Boolean(record.result)
      && reusableFinalStatus(record) !== undefined);
  }

  async updateProgress(reviewId: string, progress: Omit<ReviewProgressState, 'lastHeartbeatAt' | 'sequence'>): Promise<ReviewRecord | undefined> {
    const record = this.get(reviewId);
    if (!record || record.status !== 'IN_PROGRESS') return record;
    const next: ReviewProgressState = {
      ...progress,
      lastHeartbeatAt: new Date().toISOString(),
      sequence: (record.progress?.sequence ?? 0) + 1,
    };
    return this.save({ ...record, progress: next });
  }

  isCurrent(reviewId: string): boolean {
    const record = this.records.get(reviewId);
    if (!record) return false;
    const latest = Array.from(this.records.values())
      .filter((candidate) => candidate.projectId === record.projectId
        && candidate.subjectId === record.subjectId && candidate.stage === record.stage
        && candidate.status !== 'SUPERSEDED')
      .sort((left, right) => right.startedAt.localeCompare(left.startedAt))[0];
    return latest?.reviewId === reviewId;
  }

  async save(record: ReviewRecord): Promise<ReviewRecord> {
    return this.enqueue(() => this.saveUnlocked(record));
  }

  private async saveUnlocked(record: ReviewRecord): Promise<ReviewRecord> {
    const normalized = structuredClone({ ...record, updatedAt: new Date().toISOString() });
    const previous = this.records.get(normalized.reviewId);
    const changed = new Map<string, ReviewRecord>();
    if (!previous) {
      for (const candidate of this.records.values()) {
        if (candidate.reviewId !== normalized.reviewId
          && candidate.projectId === normalized.projectId
          && candidate.subjectId === normalized.subjectId
          && candidate.stage === normalized.stage
          && (candidate.status === 'PASSED' || candidate.status === 'PASSED_WITH_WARNINGS')) {
          changed.set(candidate.reviewId, structuredClone(candidate));
          if (candidate.result && !candidate.result.finalStatus) candidate.result.finalStatus = candidate.status;
          candidate.status = 'SUPERSEDED';
          candidate.updatedAt = normalized.updatedAt;
        }
      }
    }
    this.records.set(normalized.reviewId, normalized);
    try {
      await this.persist();
    } catch (error) {
      if (previous) this.records.set(normalized.reviewId, previous);
      else this.records.delete(normalized.reviewId);
      for (const [reviewId, snapshot] of changed) this.records.set(reviewId, snapshot);
      throw error;
    }
    return structuredClone(normalized);
  }

  /** Records left in progress by a process interruption become explicit retryable infrastructure failures. */
  async recoverInterrupted(): Promise<number> {
    const interrupted = Array.from(this.records.values()).filter((record) => record.status === 'IN_PROGRESS');
    if (interrupted.length === 0) return 0;
    const completedAt = new Date().toISOString();
    for (const record of interrupted) {
      record.status = 'REVIEW_INFRA_ERROR';
      record.completedAt = completedAt;
      record.updatedAt = completedAt;
      record.issues = [{ severity: 'ERROR', rule: 'REVIEW-INTERRUPTED', message: 'Review was interrupted by a BFF restart and must be retried.' }];
      record.audit = { ...record.audit, completedAt };
      record.progress = { phase: 'COMPLETE', message: 'Review was interrupted and must be retried.', lastHeartbeatAt: completedAt, sequence: (record.progress?.sequence ?? 0) + 1, errorCount: 1 };
    }
    await this.enqueue(() => this.persist());
    return interrupted.length;
  }

  private load(): void {
    if (!existsSync(this.filePath)) return;
    try {
      const value = JSON.parse(readFileSync(this.filePath, 'utf8')) as unknown;
      if (!isRecord(value) || !Array.isArray(value.records)) return;
      for (const candidate of value.records) {
        if (!isRecord(candidate) || typeof candidate.reviewId !== 'string') continue;
        this.records.set(candidate.reviewId, candidate as unknown as ReviewRecord);
      }
    } catch (error) {
      console.warn(`[BFF] Ignoring unreadable review store ${this.filePath}: ${errorMessage(error)}`);
    }
  }

  private async persist(): Promise<void> {
    const directory = path.dirname(this.filePath);
    await mkdir(directory, { recursive: true });
    const temporary = path.join(directory, `.${path.basename(this.filePath)}.${process.pid}.${Date.now()}.tmp`);
    const document: ReviewStoreDocument = { schemaVersion: 1, records: Array.from(this.records.values()) };
    try {
      await writeFile(temporary, `${JSON.stringify(document, null, 2)}\n`, 'utf8');
      await rename(temporary, this.filePath);
    } finally {
      await unlink(temporary).catch((error: NodeJS.ErrnoException) => {
        if (error.code !== 'ENOENT') console.warn(`[BFF] Failed to clean review-store temp file: ${error.message}`);
      });
    }
  }

  private enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.writeQueue.then(operation, operation);
    this.writeQueue = result.then(() => undefined, () => undefined);
    return result;
  }
}

function reusableFinalStatus(record: ReviewRecord): 'PASSED' | 'PASSED_WITH_WARNINGS' | 'BLOCKED' | undefined {
  const status = record.result?.finalStatus ?? (record.status === 'SUPERSEDED' ? undefined : record.status);
  return status === 'PASSED' || status === 'PASSED_WITH_WARNINGS' || status === 'BLOCKED' ? status : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export const reviewStore = new ReviewStore();
void reviewStore.recoverInterrupted().then((count) => {
  if (count > 0) console.warn(`[BFF] Recovered ${count} interrupted review record(s)`);
}).catch((error) => console.warn(`[BFF] Failed to recover interrupted reviews: ${errorMessage(error)}`));
