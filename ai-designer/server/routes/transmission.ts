/**
 * Part B 传输代理路由
 *
 * 当 PART_B_URL 未设置且 BFF_MOCK_PART_B=true 时使用 Mock 响应,便于离开 Part B 单独开发 Part A。
 * 否则透传到真实 Part B(默认 http://localhost:8111)。
 *
 * 所有透传调用都带 10s 超时,避免上游慢响应拖死 BFF。
 */

import { Router, Request, Response } from 'express';
import { requireBffAdmin } from '../security/adminGuard';
import { compilePlanContract, hashPlanBundle, hashPlanContent, hashPlanContract, hashSubPlanDescriptor, stripPlanContractBlock } from '../llm/planContract';
import { signPlanBundle } from '../llm/bundleSignature';
import { invalidateReferenceSummaryCache } from '../services/referenceSummary';
import { reviewStore } from '../services/reviewStore';

export const transmissionRouter = Router();
transmissionRouter.use((req, res, next) => {
  if (req.method === 'POST' && req.path === '/status-update') {
    next();
    return;
  }
  requireBffAdmin(req, res, next);
});

const PART_B_URL = process.env.PART_B_URL || 'http://localhost:8111';
const USE_MOCK = process.env.BFF_MOCK_PART_B === 'true';
const PROXY_TIMEOUT_MS = 20_000;
// 远程文件/时间线拉取允许更长一些(包含 LLM 响应时间)
const LONG_PROXY_TIMEOUT_MS = 60_000;

interface FetchOpts {
  method?: string;
  body?: unknown;
  timeoutMs?: number;
  /** Additional headers forwarded to Part B for privileged reference-project endpoints. */
  extraHeaders?: Record<string, string>;
}

function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  return String(e);
}

/**
 * 通用 Part B 代理调用。
 * - 自动设置超时,杜绝事件循环被卡住;
 * - 失败时返回结构化的 502 响应;
 * - 不复用 Part B 的 status code, 因为 Part B 的 4xx 对 BFF 来说仍属于上游错误。
 */
async function proxy<T>(path: string, opts: FetchOpts = {}): Promise<{ ok: true; data: T } | { ok: false; status: number; msg: string }> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), opts.timeoutMs ?? PROXY_TIMEOUT_MS);
  try {
    const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(opts.extraHeaders ?? {}) };
    const init: RequestInit = {
      method: opts.method ?? 'GET',
      headers,
      signal: controller.signal,
    };
    if (opts.body !== undefined) init.body = JSON.stringify(opts.body);
    const resp = await fetch(`${PART_B_URL}${path}`, init);
    if (!resp.ok) {
      const text = await resp.text().catch(() => '');
      return { ok: false, status: resp.status, msg: `Part B ${resp.status}: ${text.slice(0, 300)}` };
    }
    const data = (await resp.json()) as T;
    return { ok: true, data };
  } catch (e) {
    return { ok: false, status: 502, msg: `Part B 通信失败: ${errorMessage(e)}` };
  } finally {
    clearTimeout(timer);
  }
}

/** 发送方案到 Part B */
type PlanBundlePreparation =
  | { ok: true; value: Record<string, unknown> }
  | { ok: false; status: number; code: string; message: string };

export function prepareV2PlanBundle(
  value: unknown,
  signingSecret = resolveBundleSigningSecret(),
): PlanBundlePreparation {
  if (!isRecord(value) || !isRecord(value.masterPlan) || !Array.isArray(value.subPlans)
    || !isRecord(value.reviewManifest)) {
    return { ok: false, status: 400, code: 'INVALID_PLAN_BUNDLE', message: 'Plan bundle is missing masterPlan, subPlans or reviewManifest.' };
  }
  const projectId = stringValue(value.projectId);
  const masterPlanId = stringValue(value.masterPlan.id);
  const masterPlanVersion = Number(value.masterPlan.version);
  if (!projectId || !masterPlanId || !Number.isInteger(masterPlanVersion) || masterPlanVersion < 1) {
    return { ok: false, status: 400, code: 'INVALID_PLAN_BUNDLE', message: 'projectId, masterPlan.id and a positive masterPlan.version are required.' };
  }
  const requestedWriteTarget = stringValue(value.writeTarget);
  if (requestedWriteTarget && requestedWriteTarget !== 'ISOLATED') {
    return { ok: false, status: 400, code: 'REAL_WRITE_DISABLED',
      message: 'writeTarget must be ISOLATED; direct project writes are disabled.' };
  }
  const writeTarget: 'ISOLATED' = 'ISOLATED';
  const masterReviewId = stringValue(value.reviewManifest.masterReviewId);
  if (!masterReviewId) return { ok: false, status: 428, code: 'REVIEW_REQUIRED', message: 'Master review ID is required.' };
  const masterReview = reviewStore.get(masterReviewId);
  if (!masterReview || !reviewStore.isCurrent(masterReviewId) || masterReview.stage !== 'master'
    || masterReview.projectId !== projectId || masterReview.subjectId !== masterPlanId
    || (masterReview.status !== 'PASSED' && masterReview.status !== 'PASSED_WITH_WARNINGS')) {
    return { ok: false, status: 428, code: 'REVIEW_REQUIRED', message: 'Master review is missing, belongs to another subject, or is not successful.' };
  }
  const masterContent = nonBlankText(value.masterPlan.content);
  if (!masterContent || hashPlanContent(masterContent) !== masterReview.contentHash) {
    return { ok: false, status: 412, code: 'REVIEW_STALE', message: 'Master plan changed after review.' };
  }
  const masterCompilation = compilePlanContract(masterContent);
  if (hashPlanContract(masterCompilation.contract) !== masterReview.contractHash) {
    return { ok: false, status: 412, code: 'CONTRACT_STALE', message: 'Master canonical contract changed after review.' };
  }

  if (masterReview.contract.contractVersion !== '2.0' || masterReview.contract.sourceMode !== 'STRUCTURED') {
    return { ok: false, status: 422, code: 'STRUCTURED_CONTRACT_REQUIRED',
      message: 'Updated Part A transmission requires a STRUCTURED Canonical Plan Contract v2.' };
  }

  const reviewRows = Array.isArray(value.reviewManifest.subPlanReviews)
    ? value.reviewManifest.subPlanReviews.filter(isRecord) : [];
  const reviewIds = new Map<string, string | undefined>();
  for (const row of reviewRows) {
    const reviewedSubPlanId = stringValue(row.subPlanId);
    if (!reviewedSubPlanId || reviewIds.has(reviewedSubPlanId)) {
      return { ok: false, status: 400, code: 'INVALID_REVIEW_MANIFEST', message: 'Sub-plan review entries must have unique non-empty subject IDs.' };
    }
    reviewIds.set(reviewedSubPlanId, stringValue(row.reviewId));
  }
  const owners = new Map<string, string>();
  const seenSubPlanIds = new Set<string>();
  const seenIndexes = new Set<number>();
  const normalizedSubPlans: Record<string, unknown>[] = [];
  for (const candidate of value.subPlans) {
    if (!isRecord(candidate)) return { ok: false, status: 400, code: 'INVALID_SUBPLAN', message: 'Sub-plan entry is invalid.' };
    const subPlanId = stringValue(candidate.id);
    const reviewId = subPlanId ? reviewIds.get(subPlanId) : undefined;
    const reviewedContent = nonBlankText(candidate.content);
    const title = stringValue(candidate.title);
    const index = Number(candidate.index);
    if (!subPlanId || !reviewId || !reviewedContent) {
      return { ok: false, status: 428, code: 'REVIEW_REQUIRED', message: `Sub-plan ${subPlanId || '(unknown)'} has no successful review manifest.` };
    }
    if (!title || !Number.isInteger(index) || index < 1 || !isStringArray(candidate.prerequisites)) {
      return { ok: false, status: 400, code: 'INVALID_SUBPLAN', message: `Sub-plan ${subPlanId} must have a title, positive index and prerequisite array.` };
    }
    if (!seenIndexes.add(index)) {
      return { ok: false, status: 400, code: 'DUPLICATE_SUBPLAN_INDEX', message: `Sub-plan index ${index} appears more than once.` };
    }
    const prerequisites = candidate.prerequisites.map((item) => item.trim());
    if (prerequisites.some((item) => !item) || new Set(prerequisites).size !== prerequisites.length) {
      return { ok: false, status: 400, code: 'INVALID_SUBPLAN_DEPENDENCY', message: `Sub-plan ${subPlanId} has blank or duplicate prerequisites.` };
    }
    if (!seenSubPlanIds.add(subPlanId)) {
      return { ok: false, status: 400, code: 'DUPLICATE_SUBPLAN', message: `Sub-plan ${subPlanId} appears more than once.` };
    }
    const record = reviewStore.get(reviewId);
    if (!record || !reviewStore.isCurrent(reviewId) || record.stage !== 'subplan' || record.projectId !== projectId || record.subjectId !== subPlanId
      || (record.status !== 'PASSED' && record.status !== 'PASSED_WITH_WARNINGS')) {
      return { ok: false, status: 428, code: 'REVIEW_REQUIRED', message: `Sub-plan ${subPlanId} review is missing or not successful.` };
    }
    if (record.contentHash !== hashPlanContent(reviewedContent)) {
      return { ok: false, status: 412, code: 'REVIEW_STALE', message: `Sub-plan ${subPlanId} changed after review.` };
    }
    if (record.contractHash !== masterReview.contractHash) {
      return { ok: false, status: 412, code: 'CONTRACT_MISMATCH', message: `Sub-plan ${subPlanId} uses a different canonical contract.` };
    }
    if (record.rulesetVersion !== masterReview.rulesetVersion
      || record.referenceSnapshotId !== masterReview.referenceSnapshotId) {
      return { ok: false, status: 412, code: 'REVIEW_CONTEXT_MISMATCH',
        message: `Sub-plan ${subPlanId} was not reviewed with the same ruleset and reference snapshot as the master plan.` };
    }
    const currentSubCompilation = compilePlanContract(reviewedContent);
    if (currentSubCompilation.source !== 'EMBEDDED'
      || hashPlanContract(currentSubCompilation.contract) !== masterReview.contractHash) {
      return { ok: false, status: 412, code: 'CONTRACT_STALE',
        message: `Sub-plan ${subPlanId} embedded contract changed after review.` };
    }
    if (stringValue(candidate.contractHash) !== masterReview.contractHash) {
      return { ok: false, status: 412, code: 'CONTRACT_STALE',
        message: `Sub-plan ${subPlanId} contractHash changed after review.` };
    }
    const content = stripPlanContractBlock(reviewedContent);
    if (!isNonBlankStringArray(candidate.deliverableIds)) {
      return { ok: false, status: 422, code: 'DELIVERABLE_MAPPING_REQUIRED', message: `Sub-plan ${subPlanId} deliverable IDs must be a non-empty string array.` };
    }
    const deliverableIds = candidate.deliverableIds;
    if (deliverableIds.length === 0) {
      return { ok: false, status: 422, code: 'DELIVERABLE_MAPPING_REQUIRED', message: `Sub-plan ${subPlanId} has no deliverable IDs.` };
    }
    if (new Set(deliverableIds).size !== deliverableIds.length) {
      return { ok: false, status: 422, code: 'DUPLICATE_DELIVERABLE_OWNER', message: `Sub-plan ${subPlanId} assigns the same deliverable more than once.` };
    }
    for (const deliverableId of deliverableIds) {
      const deliverable = masterReview.contract.deliverables.find((item) => item.id === deliverableId);
      if (!deliverable) {
        return { ok: false, status: 422, code: 'UNKNOWN_DELIVERABLE', message: `Sub-plan ${subPlanId} references unknown deliverable ${deliverableId}.` };
      }
      if (deliverable.action === 'PROHIBIT') {
        return { ok: false, status: 422, code: 'PROHIBITED_DELIVERABLE_ASSIGNED', message: `Sub-plan ${subPlanId} assigns prohibited deliverable ${deliverableId}.` };
      }
      const owner = owners.get(deliverableId);
      if (owner && owner !== subPlanId) {
        return { ok: false, status: 422, code: 'DUPLICATE_DELIVERABLE_OWNER', message: `Deliverable ${deliverableId} is owned by both ${owner} and ${subPlanId}.` };
      }
      owners.set(deliverableId, subPlanId);
    }
    const referencedElementIds = stringArrayOrEmpty(candidate.referencedElementIds);
    const inputTypes = stringArrayOrEmpty(candidate.inputTypes);
    const outputTypes = stringArrayOrEmpty(candidate.outputTypes);
    const descriptorHash = hashSubPlanDescriptor({
      id: subPlanId, index, title, contentHash: hashPlanContent(content), prerequisites, deliverableIds,
      contractHash: masterReview.contractHash, referencedElementIds, inputTypes, outputTypes,
    });
    if (!record.subjectDescriptorHash || record.subjectDescriptorHash !== descriptorHash) {
      return { ok: false, status: 412, code: 'REVIEW_STALE',
        message: `Sub-plan ${subPlanId} descriptor or dependency graph changed after review.` };
    }
    normalizedSubPlans.push({
      ...candidate, id: subPlanId, index, title, content, prerequisites,
      contractHash: masterReview.contractHash, deliverableIds, referencedElementIds, inputTypes, outputTypes,
    });
  }
  const normalizedIds = new Set(normalizedSubPlans.map((subPlan) => String(subPlan.id)));
  for (const subPlan of normalizedSubPlans) {
    for (const prerequisite of subPlan.prerequisites as string[]) {
      if (prerequisite === subPlan.id || !normalizedIds.has(prerequisite)) {
        return { ok: false, status: 400, code: 'INVALID_SUBPLAN_DEPENDENCY',
          message: `Sub-plan ${String(subPlan.id)} has a self or unknown prerequisite ${prerequisite}.` };
      }
    }
  }
  if (hasSubPlanDependencyCycle(normalizedSubPlans)) {
    return { ok: false, status: 400, code: 'INVALID_SUBPLAN_DEPENDENCY', message: 'Sub-plan prerequisites contain a cycle.' };
  }
  const requiredIds = masterReview.contract.deliverables
    .filter((item) => item.kind !== 'OTHER' && item.action !== 'PROHIBIT').map((item) => item.id);
  const missing = requiredIds.filter((id) => !owners.has(id));
  if (missing.length > 0) {
    return { ok: false, status: 422, code: 'DELIVERABLE_COVERAGE_INCOMPLETE', message: `Missing deliverables: ${missing.join(', ')}` };
  }

  if (reviewIds.size !== normalizedSubPlans.length) {
    return { ok: false, status: 400, code: 'INVALID_REVIEW_MANIFEST', message: 'Review manifest contains subjects outside the transmitted sub-plan set.' };
  }
  const reviewManifest = {
    masterReviewId,
    masterContentHash: masterReview.contentHash,
    contractHash: masterReview.contractHash,
    rulesetVersion: masterReview.rulesetVersion,
    referenceSnapshotId: masterReview.referenceSnapshotId,
    subPlanReviews: normalizedSubPlans.map((subPlan) => ({
      subPlanId: String(subPlan.id),
      reviewId: reviewIds.get(String(subPlan.id))!,
      contentHash: hashPlanContent(String(subPlan.content)),
    })),
  };
  const generationIdentity = { ...masterReview.contract.identity };
  const bundleMaterial = {
    projectId,
    writeTarget,
    generationIdentity,
    masterPlan: { id: masterPlanId, version: masterPlanVersion, contentHash: masterReview.contentHash },
    contractHash: masterReview.contractHash,
    subPlans: normalizedSubPlans.map((subPlan) => ({
      id: String(subPlan.id),
      index: Number(subPlan.index),
      title: String(subPlan.title),
      contentHash: hashPlanContent(String(subPlan.content)),
      prerequisites: subPlan.prerequisites as string[],
      deliverableIds: subPlan.deliverableIds as string[],
      contractHash: String(subPlan.contractHash),
      referencedElementIds: subPlan.referencedElementIds as string[],
      inputTypes: subPlan.inputTypes as string[],
      outputTypes: subPlan.outputTypes as string[],
    })),
  };
  const bundleHash = hashPlanBundle(bundleMaterial);
  if (!signingSecret.trim()) {
    return {
      ok: false, status: 503, code: 'BUNDLE_SIGNING_UNAVAILABLE',
      message: 'PLAN_BUNDLE_SIGNING_SECRET must be configured before a reviewed v2 bundle can be transmitted.',
    };
  }
  const bundleSignature = signPlanBundle(bundleHash, reviewManifest, signingSecret);
  return {
    ok: true,
    value: {
      ...value,
      projectId,
      writeTarget,
      generationIdentity,
      masterPlan: { ...value.masterPlan, id: masterPlanId, version: masterPlanVersion, content: masterContent },
      subPlans: normalizedSubPlans,
      canonicalContract: masterReview.contract,
      reviewManifest,
      bundleHash,
      bundleSignature,
    },
  };
}

function resolveBundleSigningSecret(): string {
  return process.env.PLAN_BUNDLE_SIGNING_SECRET
    || process.env.AI_WORKFLOW_BUNDLE_SIGNING_SECRET
    || '';
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function nonBlankText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

function stringArrayOrEmpty(value: unknown): string[] {
  return isStringArray(value) ? value.map((item) => item.trim()).filter(Boolean) : [];
}

function hasSubPlanDependencyCycle(subPlans: Record<string, unknown>[]): boolean {
  const prerequisites = new Map(subPlans.map((subPlan) => [
    String(subPlan.id), subPlan.prerequisites as string[],
  ]));
  const visiting = new Set<string>();
  const visited = new Set<string>();
  const visit = (id: string): boolean => {
    if (visiting.has(id)) return true;
    if (visited.has(id)) return false;
    visiting.add(id);
    for (const dependency of prerequisites.get(id) ?? []) if (visit(dependency)) return true;
    visiting.delete(id);
    visited.add(id);
    return false;
  };
  return Array.from(prerequisites.keys()).some(visit);
}

function isNonBlankStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.length > 0
    && value.every((item) => typeof item === 'string' && Boolean(item.trim()));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

transmissionRouter.post('/send', async (req: Request, res: Response) => {
  const prepared = prepareV2PlanBundle(req.body);
  if (!prepared.ok) {
    res.status(prepared.status).json({ code: prepared.status, success: false, error: prepared.code, msg: prepared.message });
    return;
  }
  const planData = prepared.value;

  // Mock 模式: 显式通过 BFF_MOCK_PART_B=true 启用 — 避免生产环境因 PART_B_URL 漏配静默走 Mock
  if (USE_MOCK) {
    const receptionId = `rec_${Date.now()}`;
    const subPlanStatuses: Record<string, string> = {};
    (Array.isArray(planData.subPlans) ? planData.subPlans : []).forEach((sp) => {
      subPlanStatuses[sp.id] = 'QUEUED';
    });

    res.json({
      code: 200,
      success: true,
      data: { receptionId, status: 'RECEIVED', subPlanStatuses },
      msg: '方案已接收 (Mock模式)',
    });
    return;
  }

  // 真实调用 Part B
  // Forward the reviewed plan to Part B; generation is always isolated.
  const result = await proxy<unknown>('/api/plans/receive', {
    method: 'POST',
    body: planData,
    timeoutMs: LONG_PROXY_TIMEOUT_MS,
  });
  if (!result.ok) {
    res.status(502).json({ code: 502, success: false, msg: result.msg });
    return;
  }
  res.json(result.data);
});

/** 查询 Part B 执行状态 */
transmissionRouter.get('/status/:receptionId', async (req: Request, res: Response) => {
  const { receptionId } = req.params;

  if (USE_MOCK) {
    res.json({
      code: 200,
      success: true,
      data: {
        receptionId,
        projectId: 'mock-project',
        overallStatus: 'EXECUTING',
        subPlanUpdates: [
          { subPlanId: 'sub_1', status: 'COMPLETED', gitCommitHash: 'abc123', completedAt: new Date().toISOString() },
          { subPlanId: 'sub_2', status: 'EXECUTING' },
        ],
      },
      msg: 'Mock状态数据',
    });
    return;
  }

  const result = await proxy<unknown>(`/api/plans/status?receptionId=${encodeURIComponent(receptionId)}`);
  if (!result.ok) {
    res.status(502).json({ success: false, msg: result.msg });
    return;
  }
  res.json(result.data);
});

/** 接收 Part B 的状态回调 — 当前仅落日志(后续可桥接 WebSocket 推前端) */
transmissionRouter.post('/status-update', (req: Request, res: Response) => {
  const update = req.body;
  // 简短日志,避免把整个 body 都打出来
  console.log(
    `[Part B 状态回调] receptionId=${update?.receptionId} overall=${update?.overallStatus} subUpdates=${(update?.subPlanUpdates || []).length}`
  );
  res.json({ success: true, msg: '状态更新已接收' });
});

/** 透传读取 Part B 的 LLM 配置(脱敏),给前端配置 Modal 显示用 */
transmissionRouter.get('/partb-config', async (_req: Request, res: Response) => {
  const result = await proxy<unknown>('/api/config/llm');
  if (!result.ok) {
    res.status(502).json({ success: false, msg: result.msg });
    return;
  }
  res.json(result.data);
});

/** 列出 Part B 中该 receptionId 下所有生成的代码文件(摘要,不含内容) */
transmissionRouter.get('/files/:receptionId', async (req: Request, res: Response) => {
  const { receptionId } = req.params;
  const result = await proxy<unknown>(`/api/plans/${encodeURIComponent(receptionId)}/files`);
  if (!result.ok) {
    res.status(502).json({ success: false, msg: result.msg });
    return;
  }
  res.json(result.data);
});

/** 单文件完整内容 */
transmissionRouter.get('/file/:fileId', async (req: Request, res: Response) => {
  const { fileId } = req.params;
  const result = await proxy<unknown>(`/api/plans/files/${encodeURIComponent(fileId)}`);
  if (!result.ok) {
    res.status(502).json({ success: false, msg: result.msg });
    return;
  }
  res.json(result.data);
});

/** 执行进度时间线 */
transmissionRouter.get('/timeline/:receptionId', async (req: Request, res: Response) => {
  const { receptionId } = req.params;
  const result = await proxy<unknown>(`/api/plans/${encodeURIComponent(receptionId)}/timeline`);
  if (!result.ok) {
    res.status(502).json({ success: false, msg: result.msg });
    return;
  }
  res.json(result.data);
});

// Reference project configuration and read-only source browsing.

/** 设置参考项目路径并扫描(需 Part B 鉴权) */
transmissionRouter.post('/reference', async (req: Request, res: Response) => {
  const adminToken = process.env.AI_WORKFLOW_ADMIN_TOKEN || '';
  const extraHeaders: Record<string, string> = {};
  if (adminToken) extraHeaders['X-Admin-Token'] = adminToken;
  const result = await proxy<unknown>('/api/project/reference', {
    method: 'POST',
    body: req.body,
    timeoutMs: 60_000, // 扫描大项目可能耗时
    extraHeaders,
  });
  if (!result.ok) {
    res.status(502).json({ success: false, msg: result.msg });
    return;
  }
  invalidateReferenceSummaryCache();
  res.json(result.data);
});

/** 查询参考项目状态(只读,无鉴权) */
transmissionRouter.get('/reference', async (_req: Request, res: Response) => {
  const result = await proxy<unknown>('/api/project/reference');
  if (!result.ok) {
    res.status(502).json({ success: false, msg: result.msg });
    return;
  }
  res.json(result.data);
});

/** 浏览本机目录(选参考项目路径,需鉴权 — 转发带 X-Admin-Token) */
transmissionRouter.get('/browse', async (req: Request, res: Response) => {
  const adminToken = process.env.AI_WORKFLOW_ADMIN_TOKEN || '';
  const extraHeaders: Record<string, string> = {};
  if (adminToken) extraHeaders['X-Admin-Token'] = adminToken;
  // 透传 path 查询参数
  const path = (req.query.path as string) || '';
  const qs = path ? `?path=${encodeURIComponent(path)}` : '';
  const result = await proxy<unknown>(`/api/project/browse${qs}`, { extraHeaders });
  if (!result.ok) {
    res.status(502).json({ success: false, msg: result.msg });
    return;
  }
  res.json(result.data);
});
