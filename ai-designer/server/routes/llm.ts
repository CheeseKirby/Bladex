/**
 * LLM 代理路由
 *
 * 真实 LLM 调用走 Anthropic Messages API,与 ccswitch / Claude Code 一致。
 * 配置由 server/config/llmConfig.ts 管理(支持运行时通过 /api/config/llm 修改)。
 *
 * 错误传播策略:
 * - 流式接口在尚未写出任何 SSE 帧前,若发现上游失败,直接返回 502 JSON 让前端能正常感知错误;
 * - 一旦已经开始流式输出,出错改用 SSE 帧 {type:'error'} 通知前端;
 * - 客户端关闭连接时,通过 AbortController 取消上游 fetch,避免上游 socket 与配额泄漏。
 */

import { randomUUID } from 'node:crypto';
import { Router, Request, Response } from 'express';
import { buildAuthHeaders, getLlmConfig, isLlmConfigured } from '../config/llmConfig';
import { requireBffAdmin } from '../security/adminGuard';
import { createPayloadGuard } from '../http/payloadGuard';
import { createRateLimitMiddleware } from '../http/rateLimit';
import { bindUpstreamAbort } from '../http/upstreamAbort';
import { fetchWithTransientRetry } from '../http/fetchRetry';
import { consumeAnthropicStream } from '../llm/anthropicStream';
import {
  parseReviewModelResponseWithRecovery,
  REVIEW_RULESET_VERSION,
  type ReviewAuditEvidence,
  type ReviewRoundEvidence,
} from '../llm/reviewProtocol';
import {
  buildCanonicalReferenceIntent,
  formatReferenceReviewEvidence,
  formatReferenceReviewEvidenceForGeneration,
  formatReferenceReviewEvidenceForSemanticReview,
  getReferenceAdaptationSummary,
  getReferenceReviewContext,
  getReferenceReviewEvidence,
} from '../services/referenceSummary';
import {
  applyPlanRepairOperations,
  compilePlanContract,
  formatDeterministicIssues,
  hashPlanContent,
  hashPlanContract,
  hashSubPlanDescriptor,
  planSafeDeterministicRepairs,
  renderCanonicalContractSummary,
  stripPlanContractBlock,
  upsertPlanContractBlock,
  validateNarrativeContractConsistency,
  validatePlanContract,
  withContractReviewMetadata,
  type DeterministicPlanIssue,
  type PlanRepairOperation,
  type SubPlanDescriptorHashMaterial,
} from '../llm/planContract';
import { parseSplitModelResponse, parseSplitModelResponseWithRecovery } from '../llm/splitProtocol';
import { gateSemanticIssues, normalizeRule } from '../llm/semanticIssueGate';
import { compileStructuredPlanDraft, isPlanDraftGenerationBlockingIssue, normalizePlanDraftAgainstRequirement, parsePlanDraftResponse, renderStructuredPlan, type PlanDraftV2 } from '../llm/planDraft';
import { assertSingleConfiguredEntity, normalizeOneShotSuggestions } from '../llm/oneShotNormalization';
import { compileConfiguredPlanDraft } from '../llm/configuredPlanDraft';
import { applyReferenceGrounding, groundPlanDraftWithReferenceEvidence } from '../llm/referenceGrounding';
import { buildSemanticReviewSubject } from '../llm/reviewSubject';
import { reviewStore, type ReviewRecord, type ReviewPhase } from '../services/reviewStore';

export const llmRouter = Router();
const activeReviewControllers = new Map<string, AbortController>();

// LLM calls consume privileged server-side credentials and must never be an open proxy.
llmRouter.use(requireBffAdmin);
const llmRateLimit = createRateLimitMiddleware({
  maxRequests: Number(process.env.BFF_LLM_RATE_LIMIT || 30),
  windowMs: Number(process.env.BFF_LLM_RATE_WINDOW_MS || 60_000),
});
llmRouter.use((req, res, next) => req.path.startsWith('/review-status')
  ? next() : llmRateLimit(req, res, next));
llmRouter.use(createPayloadGuard());

const LLM_DEFAULT_REQUEST_TIMEOUT_MS = 240_000;
const LLM_LONG_REQUEST_TIMEOUT_MS = 600_000; // 10 分钟(大方案审查/拆分非流式 LLM 生成完整 JSON 较慢,5min 曾导致 abort)
const PLAN_DRAFT_MAX_TOKENS = 6_000;
const REVIEW_HEARTBEAT_MS = Math.max(5_000, Number(process.env.BFF_REVIEW_HEARTBEAT_MS || 15_000));

interface ModuleSummary { type: string; name: string; icon: string; config?: unknown }

function reviewStatusPayload(record: ReviewRecord): object {
  return {
    reviewId: record.reviewId,
    projectId: record.projectId,
    subjectId: record.subjectId,
    stage: record.stage,
    status: record.status,
    contractHash: record.contractHash,
    referenceSnapshotId: record.referenceSnapshotId,
    rulesetVersion: record.rulesetVersion,
    issues: record.issues,
    progress: record.progress,
    startedAt: record.startedAt,
    completedAt: record.completedAt,
    updatedAt: record.updatedAt,
    ...(record.result ? {
      result: {
        reviewId: record.reviewId,
        contractHash: record.contractHash,
        status: record.result.finalStatus ?? record.status,
        passes: record.result.passes,
        issues: record.issues,
        fixedContent: record.result.fixedContent,
        reviewLog: record.result.reviewLog,
        changeLog: record.result.changeLog,
        audit: record.audit,
        cacheHit: record.result.cacheHit ?? false,
      },
    } : {}),
  };
}

llmRouter.get('/review-status/:reviewId', (req: Request, res: Response) => {
  const record = reviewStore.get(req.params.reviewId);
  if (!record) {
    res.status(404).json({ success: false, code: 'REVIEW_NOT_FOUND', error: 'Review record was not found.' });
    return;
  }
  res.json({ success: true, data: reviewStatusPayload(record) });
});

llmRouter.get('/review-status', (req: Request, res: Response) => {
  const projectId = typeof req.query.projectId === 'string' ? req.query.projectId.trim() : '';
  const subjectId = typeof req.query.subjectId === 'string' ? req.query.subjectId.trim() : '';
  const stage = req.query.stage === 'subplan' ? 'subplan' : req.query.stage === 'master' ? 'master' : undefined;
  if (!projectId || !subjectId || !stage) {
    res.status(400).json({ success: false, code: 'INVALID_REVIEW_SUBJECT', error: 'projectId, subjectId and stage are required.' });
    return;
  }
  const record = reviewStore.latest(projectId, subjectId, stage);
  if (!record) {
    res.status(404).json({ success: false, code: 'REVIEW_NOT_FOUND', error: 'Review record was not found.' });
    return;
  }
  res.json({ success: true, data: reviewStatusPayload(record) });
});

llmRouter.post('/review-status/:reviewId/cancel', async (req: Request, res: Response) => {
  const record = reviewStore.get(req.params.reviewId);
  if (!record) {
    res.status(404).json({ success: false, code: 'REVIEW_NOT_FOUND', error: 'Review record was not found.' });
    return;
  }
  if (record.status !== 'IN_PROGRESS') {
    res.json({ success: true, data: reviewStatusPayload(record) });
    return;
  }
  activeReviewControllers.get(record.reviewId)?.abort(new Error('Review cancelled by user'));
  await reviewStore.updateProgress(record.reviewId, { phase: 'FINALIZING', message: 'Cancellation requested by user.' });
  res.json({ success: true, data: { reviewId: record.reviewId, status: 'CANCELLING' } });
});

function buildGeneratePlanSystemPrompt(): string {
  return `You are a senior BladeX backend architect. Convert the requirement and configured modules into one compact JSON object only.

Schema:
{
  "identity":{"moduleName":"lowercase","entityName":"PascalCase","tableName":"snake_case","basePackage":"org.springblade.module"},
  "title":"plan title",
  "requirementSummary":"business goal and key rules",
  "fields":[{"name":"camelCase","columnName":"snake_case","javaType":"String|Long|Integer|Date|Boolean|BigDecimal|LocalDateTime","required":true,"role":"PERSISTENT|DERIVED","description":"meaning"}],
  "states":[{"name":"businessState","values":["DRAFT","APPROVED"],"transitions":[{"from":"DRAFT","to":"APPROVED","trigger":"submit"}],"referenceField":"optional existing state field"}],
  "integrations":[{"type":"API|FEIGN|WORKFLOW|EVENT|OTHER","sourceModule":"module","targetModule":"optional module","entrypoint":"ConcreteClass.method or /path"}],
  "deliverables":[{"kind":"DDL|ENTITY|VO|MAPPER|SERVICE|CONTROLLER|FEIGN|EXCEL|CONFIG|OTHER","className":"optional class","moduleSide":"API|IMPL|DOC|UNKNOWN","action":"CREATE|MODIFY|EXTEND|PROHIBIT"}],
  "architectureDecisions":[{"decision":"explicit decision","rationale":"why","evidence":["reference symbol or requirement"]}]
}

Hard rules:
- Return JSON only, without Markdown or code fences.
- Configured table/module/entity/field values are immutable constraints.
- Include persistent business fields exactly once; BaseEntity audit fields are not business fields.
- Required deliverables: exactly one DDL, ENTITY, MAPPER, SERVICE and CONTROLLER, plus at least one VO. Add FEIGN/EXCEL/CONFIG only when required.
- ENTITY className must equal identity.entityName. SERVICE represents both the I{Entity}Service interface and {Entity}ServiceImpl; never emit ServiceImpl as a second SERVICE deliverable.
- Each VO deliverable names one physical VO class. Do not claim or repeat an implicit VO family in the draft. Export/Excel classes must not be declared as ENTITY.
- Every integration must have a concrete entrypoint. Never use vague text such as "integrate later".
- When reference evidence assigns ownership to an existing module, bind/extend it or leave an explicit unresolved architecture decision; do not silently create a parallel capability.
- Java/framework/package conventions from the reference profile override generic defaults.
- Do not invent architecture evidence.`;
}

function buildSplitPlanSystemPrompt(): string {
  return `You organize an already-reviewed Canonical Plan Contract v2 into dependency-ordered sub-plans.

Return JSON only:
{
  "subPlans": [{
    "id": "sub_1",
    "index": 1,
    "title": "short task boundary",
    "planContent": "explanation of the assigned canonical deliverables",
    "prerequisites": [],
    "deliverableIds": ["deliverable.ddl.1"],
    "referencedElementIds": ["entity.order", "field.order.order-no"]
  }]
}

Hard rules:
- Use exact deliverable and contract element IDs from the supplied plan-contract.
- Assign every active required deliverable exactly once; never assign a PROHIBIT deliverable.
- Do not create modules, entities, fields, types, services, states, integrations or deliverables.
- A sub-plan that consumes a type must have the provider sub-plan in its prerequisite transitive closure.
- prerequisites must form an acyclic graph with contiguous indexes 1..N.
- Titles and prose are explanatory only and never replace deliverableIds.
- Keep the canonical identity unchanged in every sub-plan.`;
}

function buildSplitSchemaRecoverySystemPrompt(): string {
  return `${buildSplitPlanSystemPrompt()}

Repair one malformed split response into the exact JSON schema above.
- Preserve the original task boundaries and prose where possible.
- Convert prerequisites to arrays of exact sub-plan string ids.
- Use only deliverableIds and referencedElementIds present in the supplied canonical contract.
- Do not add, remove, duplicate or reassign canonical deliverables.
- Return JSON only; arrays must never be null.`;
}

function buildReviewPlanSystemPrompt(stage: string): string {
  return `You are a senior BladeX architecture reviewer. Review the supplied ${stage === 'master' ? 'master plan' : 'sub-plan'} against its canonical plan-contract and the authoritative deterministic findings.

Return JSON only with exactly this shape:
{
  "passes": true|false,
  "issues": [{ "severity": "ERROR"|"WARN", "rule": "registered rule id", "message": "concise message", "elementIds": ["contract element id"], "evidence": { "source": "DETERMINISTIC_RULE"|"CONTRACT_INVARIANT"|"REFERENCE_DECISION", "expected": "optional", "actual": "optional" } }],
  "repairs": [{
    "operation": "ADD_MODULE|MOVE_ENTITY|SPLIT_AGGREGATE|BIND_EXISTING_SYMBOL|ADD_REFERENCE_BINDING|ADD_DELIVERABLE|ADD_INTEGRATION|CHANGE_STATE_OWNER|DECLARE_ARCHITECTURE_DECISION|RENAME_ENTITY",
    "targetId": "contract element id when required",
    "arguments": {},
    "resolves": ["RULE-ID"],
    "preconditions": ["concrete precondition"]
  }],
  "fixes": [],
  "changeLog": []
}

Rules:
- Deterministic findings are authoritative and cannot be waived.
- A new semantic ERROR must identify existing elementIds and structured evidence that the server can independently verify. Unsupported semantic claims must be WARN.
- Never use a new spelling or alias for an existing deterministic rule id.
- For each deterministic ERROR, return a typed repair against an existing plan-contract id, or leave it as an unresolved ERROR when no safe repair exists.
- Never invent target ids. Use only ids from the supplied plan-contract.
- Prefer binding/extending reference symbols over creating parallel capabilities.
- Review only semantic invariants not already owned by deterministic validation: domain/module ownership, aggregate boundaries, reference binding relevance, state ownership, and concrete integration entrypoints.
- Do not re-analyze type closure, hashes, schema validity, deliverable topology, source code, formatting, or framework compatibility; deterministic validators own those checks.
- When deterministic ERROR is zero and the semantic projection contains no independently verifiable contradiction, return passes=true immediately with empty arrays.
- Keep the response compact: at most 12 issues and 12 repairs.
- Repair arguments must contain only fields required by the selected operation. Do not repeat plan chapters, source code, or the complete contract.
- fixes and changeLog must be empty arrays. Do not return fixedContent.
- passes=true only when there are no ERROR issues, and then repairs must be empty.
- Arrays must never be null. If review cannot be completed, return one concise ERROR issue and no repair.`;
}


function buildFastSemanticReviewSystemPrompt(stage: string): string {
  return `Perform one focused semantic audit of the supplied ${stage === 'master' ? 'master plan' : 'sub-plan'} semantic projection.
Deterministic validation has already passed. Inspect only: module/domain ownership, aggregate boundary coherence, reference-binding relevance, state ownership, and concrete integration entrypoints.
Return JSON only in exactly this shape:
{"passes":true|false,"issues":[{"severity":"ERROR"|"WARN","rule":"registered rule id","message":"concise message","elementIds":["existing contract id"],"evidence":{"source":"CONTRACT_INVARIANT"|"REFERENCE_DECISION","expected":"optional","actual":"optional"}}],"repairs":[],"fixes":[],"changeLog":[]}
If no independently verifiable contradiction exists, return exactly {"passes":true,"issues":[],"repairs":[],"fixes":[],"changeLog":[]} immediately.
Do not explain your reasoning. Do not inspect types, hashes, schemas, deliverable topology, framework compatibility, formatting, or source code. Never invent element ids or rule aliases. Maximum 6 issues.`;
}

function buildReviewSchemaRecoverySystemPrompt(): string {
  return `Repair a malformed review response into one compact JSON object. Preserve intended issues and typed repairs, but fix JSON syntax and enforce this schema exactly:
{
  "passes": boolean,
  "issues": [{"severity":"ERROR"|"WARN","rule":"registered rule id","message":"concise message","elementIds":["contract id"],"evidence":{"source":"DETERMINISTIC_RULE"|"CONTRACT_INVARIANT"|"REFERENCE_DECISION","expected":"optional","actual":"optional"}}],
  "repairs": [{"operation":"ADD_MODULE|MOVE_ENTITY|SPLIT_AGGREGATE|BIND_EXISTING_SYMBOL|ADD_REFERENCE_BINDING|ADD_DELIVERABLE|ADD_INTEGRATION|CHANGE_STATE_OWNER|DECLARE_ARCHITECTURE_DECISION|RENAME_ENTITY","targetId":"optional id","arguments":{},"resolves":["RULE-ID"],"preconditions":["concrete precondition"]}],
  "fixes": [],
  "changeLog": []
}
Return JSON only. Maximum 12 issues and 12 repairs. Never return fixedContent, Markdown, code fences, prose, null arrays, or invented plan element ids.`;
}

// ==================== /generate-plan ====================

llmRouter.post('/generate-plan', async (req: Request, res: Response) => {
  if (!isLlmConfigured()) {
    await handleMockGeneratePlan(req, res);
    return;
  }
  await handleLiveGeneratePlan(req, res);
});

// ==================== /review-plan (非流式 JSON) ====================

llmRouter.post('/review-plan', async (req: Request, res: Response) => {
  const { planContent, stage, projectId: requestedProjectId, subjectId: requestedSubjectId, subjectDescriptor,
    parentReviewId: requestedParentReviewId } = req.body as {
    planContent: string;
    stage: 'master' | 'subplan';
    projectId?: string;
    subjectId?: string;
    parentReviewId?: string;
    subjectDescriptor?: Omit<SubPlanDescriptorHashMaterial, 'contentHash'>;
  };
  const projectId = requestedProjectId?.trim();
  const subjectId = requestedSubjectId?.trim();
  if (!planContent?.trim() || (stage !== 'master' && stage !== 'subplan') || !projectId || !subjectId) {
    res.status(400).json({ success: false, code: 'INVALID_REVIEW_SUBJECT',
      error: 'planContent, stage, projectId and subjectId are required for a persisted review' });
    return;
  }

  const requestCompilation = compilePlanContract(planContent);
  const requestContentHash = hashPlanContent(planContent);
  const requestContractHash = hashPlanContract(requestCompilation.contract);

  // SSE review progress is recoverable through the persisted review-status endpoint.
  let normalizedSubjectDescriptor: SubPlanDescriptorHashMaterial | undefined;
  let subjectDescriptorHash: string | undefined;
  let parentReviewRecord: ReturnType<typeof reviewStore.get>;
  if (stage === 'subplan') {
    const descriptor = normalizeReviewSubjectDescriptor(subjectDescriptor, planContent, subjectId);
    if (!descriptor.ok) {
      res.status(400).json({ success: false, code: 'INVALID_REVIEW_DESCRIPTOR', error: descriptor.error });
      return;
    }
    const currentContractHash = requestContractHash;
    if (descriptor.value.contractHash !== currentContractHash) {
      res.status(412).json({ success: false, code: 'CONTRACT_STALE', error: 'Sub-plan descriptor contractHash does not match its embedded contract.' });
      return;
    }
    normalizedSubjectDescriptor = descriptor.value;
    subjectDescriptorHash = hashSubPlanDescriptor(descriptor.value);
    const parentReviewId = requestedParentReviewId?.trim();
    parentReviewRecord = parentReviewId ? reviewStore.get(parentReviewId) : reviewStore.list(projectId)
      .find((record) => record.stage === 'master' && reviewStore.isCurrent(record.reviewId)
        && (record.status === 'PASSED' || record.status === 'PASSED_WITH_WARNINGS')
        && record.contractHash === currentContractHash);
    if (!parentReviewRecord || !reviewStore.isCurrent(parentReviewRecord.reviewId)
      || parentReviewRecord.stage !== 'master' || parentReviewRecord.projectId !== projectId
      || (parentReviewRecord.status !== 'PASSED' && parentReviewRecord.status !== 'PASSED_WITH_WARNINGS')) {
      res.status(428).json({ success: false, code: 'REVIEW_REQUIRED',
        error: 'A current successful master review is required before reviewing a sub-plan.' });
      return;
    }
    if (parentReviewRecord.contractHash !== currentContractHash
      || parentReviewRecord.rulesetVersion !== REVIEW_RULESET_VERSION) {
      res.status(412).json({ success: false, code: 'REVIEW_CONTEXT_MISMATCH',
        error: 'Sub-plan contract or ruleset does not match the current master review.' });
      return;
    }
  }
  let reviewReferenceSnapshotId = parentReviewRecord?.referenceSnapshotId;
  const reviewId = randomUUID();
  const reviewStartedAt = new Date().toISOString();
  const queuedCanonicalContent = upsertPlanContractBlock(planContent, requestCompilation.contract);
  const queuedCanonicalContract = compilePlanContract(queuedCanonicalContent).contract;
  try {
    const started = await reviewStore.begin({
      reviewId, projectId, subjectId, stage, status: 'IN_PROGRESS',
      contentHash: requestContentHash, contractHash: requestContractHash,
      subjectDescriptorHash, contract: queuedCanonicalContract,
      referenceSnapshotId: reviewReferenceSnapshotId,
      rulesetVersion: REVIEW_RULESET_VERSION, issues: [],
      audit: buildReviewAudit(reviewId, reviewStartedAt, []),
      progress: { phase: 'QUEUED', message: 'Review queued.', lastHeartbeatAt: reviewStartedAt, sequence: 0 },
      startedAt: reviewStartedAt, updatedAt: reviewStartedAt,
    });
    if (!started.created) {
      res.setHeader('X-Review-Id', started.record.reviewId);
      res.status(409).json({ success: false, code: 'REVIEW_IN_PROGRESS',
        error: 'An identical review is already in progress.', reviewId: started.record.reviewId });
      return;
    }
  } catch (error) {
    res.status(500).json({ success: false, code: 'REVIEW_STORE_ERROR',
      error: `Unable to persist the review task: ${error instanceof Error ? error.message : String(error)}` });
    return;
  }

  res.setHeader('X-Review-Id', reviewId);
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  res.flushHeaders();
  res.socket?.setNoDelay(true);
  const sendSSE = (obj: object) => {
    if (!res.destroyed && !res.writableEnded) res.write(`data: ${JSON.stringify(obj)}\n\n`);
  };
  const totalRounds = 1;
  const reviewRoundEvidence: ReviewRoundEvidence[] = [];
  const sendProgress = (
    stage: 'preparing' | 'reviewing' | 'analyzing' | 'fixing' | 'complete',
    message: string,
    round = 1,
    details: { errorCount?: number; warningCount?: number } = {},
  ) => {
    const phase: ReviewPhase = stage === 'preparing' ? 'REFERENCE_EVIDENCE'
      : stage === 'reviewing' ? 'SEMANTIC_REVIEW'
        : stage === 'fixing' ? 'DETERMINISTIC_VALIDATION'
          : stage === 'analyzing' ? 'FINALIZING' : 'COMPLETE';
    sendSSE({ type: 'progress', reviewId, stage, message, round, totalRounds, ...details });
    void reviewStore.updateProgress(reviewId, { phase, message, ...details }).catch((error) => {
      console.warn(`[/review-plan] failed to persist progress for ${reviewId}: ${error instanceof Error ? error.message : String(error)}`);
    });
  };

  if (!isLlmConfigured()) {
    try {
    const initialMockContent = queuedCanonicalContent;
    const initialMockContract = queuedCanonicalContract;
    sendProgress('preparing', 'Loading deterministic review rules and reference evidence (Mock)...');
    const referenceEvidence = parentReviewRecord
      ? { adaptationSummary: await getReferenceAdaptationSummary(), search: null, searchStatus: 'SUCCESS' as const, searchDurationMs: 0 }
      : await getReferenceReviewEvidence(buildCanonicalReferenceIntent(initialMockContract));
    reviewReferenceSnapshotId ??= referenceEvidence.search?.snapshotId;
    let mockContent = planContent;
    let mockCompilation = compilePlanContract(mockContent);
    let mockIssues = validatePlanContract(mockCompilation, referenceEvidence, mockContent);
    const safeRepairs = planSafeDeterministicRepairs(mockCompilation.contract, mockIssues, referenceEvidence);
    if (safeRepairs.length > 0) {
      const repairResult = applyPlanRepairOperations(mockCompilation.contract, safeRepairs);
      if (repairResult.applied.length > 0 && repairResult.rejected.length === 0) {
        mockContent = upsertPlanContractBlock(mockContent, repairResult.contract);
        mockCompilation = compilePlanContract(mockContent);
        mockIssues = validatePlanContract(mockCompilation, referenceEvidence, mockContent);
      }
    }
    let mockContract = withContractReviewMetadata(mockCompilation.contract, {
      referenceSnapshotId: reviewReferenceSnapshotId,
      rulesetVersion: REVIEW_RULESET_VERSION,
    });
    mockContent = renderCanonicalContractSummary(mockContent, mockContract);
    mockCompilation = compilePlanContract(mockContent);
    mockContract = withContractReviewMetadata(mockCompilation.contract, {
      referenceSnapshotId: reviewReferenceSnapshotId,
      rulesetVersion: REVIEW_RULESET_VERSION,
    });
    mockContent = upsertPlanContractBlock(mockContent, mockContract);
    mockIssues = [
      ...validatePlanContract(compilePlanContract(mockContent), referenceEvidence, mockContent),
      ...validateNarrativeContractConsistency(mockContent, mockContract),
      { severity: 'WARN', rule: 'MOCK-SEMANTIC-REVIEW-UNAVAILABLE', message: 'Semantic model review was unavailable; only deterministic review rules were executed.' },
    ];
    const mockErrorCount = mockIssues.filter((issue) => issue.severity === 'ERROR').length;
    const mockStatus = mockErrorCount === 0 ? 'REVIEW_INFRA_ERROR' : 'BLOCKED';
    if (mockStatus === 'REVIEW_INFRA_ERROR') {
      mockIssues.push({ severity: 'ERROR', rule: 'REVIEW-INFRA',
        message: 'Semantic review model is not configured; deterministic-only review cannot issue a passing credential.' });
    }
    const mockWarningCount = mockIssues.filter((issue) => issue.severity === 'WARN').length;
    const mockAudit = buildReviewAudit(reviewId, reviewStartedAt, []);
    if (normalizedSubjectDescriptor) {
      subjectDescriptorHash = hashSubPlanDescriptor({ ...normalizedSubjectDescriptor,
        contentHash: hashPlanContent(mockContent), contractHash: hashPlanContract(mockContract) });
    }
    await reviewStore.save({
      reviewId, projectId, subjectId, stage: stage || 'master', status: mockStatus,
      contentHash: hashPlanContent(mockContent), contractHash: hashPlanContract(mockContract), subjectDescriptorHash, contract: mockContract,
      referenceSnapshotId: reviewReferenceSnapshotId,
      rulesetVersion: REVIEW_RULESET_VERSION, issues: mockIssues, audit: mockAudit,
      progress: { phase: 'COMPLETE', message: mockStatus === 'BLOCKED' ? 'Mock review blocked.' : 'Review infrastructure unavailable.',
        lastHeartbeatAt: mockAudit.completedAt, sequence: 1, errorCount: mockErrorCount, warningCount: mockWarningCount },
      ...(mockStatus === 'BLOCKED' ? { result: {
        finalStatus: 'BLOCKED' as const,
        passes: false,
        fixedContent: mockContent,
        reviewLog: [{ round: 1, action: 'deterministic-review', errorCount: mockErrorCount,
          message: `Mock deterministic review completed with ${mockErrorCount} ERROR` }],
        changeLog: safeRepairs.map((repair) => ({ what: repair.operation,
          why: repair.resolves.join(', ') || 'mechanically provable repair', before: repair.targetId || '', after: JSON.stringify(repair.arguments) })),
      } } : {}),
      startedAt: reviewStartedAt, completedAt: mockAudit.completedAt, updatedAt: mockAudit.completedAt,
    });
    sendProgress('reviewing', 'Running deterministic mock review...');
    sendProgress('analyzing', `Mock review completed: ${mockErrorCount} ERROR, ${mockWarningCount} WARN`, 1,
      { errorCount: mockErrorCount, warningCount: mockWarningCount });
    sendProgress('complete', mockErrorCount === 0 ? 'Review infrastructure unavailable' : 'Mock deterministic review blocked', 1,
      { errorCount: mockErrorCount, warningCount: mockWarningCount });
    if (mockErrorCount === 0) {
      sendSSE({ type: 'error', code: 'REVIEW_INFRA_ERROR',
        message: 'Semantic review model is not configured; deterministic-only review cannot issue a passing credential.', audit: mockAudit });
    } else {
      sendSSE({
        type: 'done',
        data: {
          reviewId,
          contractHash: hashPlanContract(mockContract),
          status: mockStatus,
          passes: false,
          issues: mockIssues,
          fixedContent: mockContent,
          reviewLog: [{ round: 1, action: 'deterministic-review', errorCount: mockErrorCount,
            message: `Mock deterministic review completed with ${mockErrorCount} ERROR` }],
          changeLog: safeRepairs.map((repair) => ({
            what: repair.operation,
            why: repair.resolves.join(', ') || 'mechanically provable repair',
            before: repair.targetId || '',
            after: JSON.stringify(repair.arguments),
          })),
          audit: mockAudit,
        },
      });
    }
      res.end();
    } catch (error) {
      const failureMessage = error instanceof Error ? error.message : String(error);
      const audit = buildReviewAudit(reviewId, reviewStartedAt, []);
      await reviewStore.save({
        reviewId, projectId, subjectId, stage, status: 'REVIEW_INFRA_ERROR',
        contentHash: requestContentHash, contractHash: requestContractHash,
        subjectDescriptorHash, contract: queuedCanonicalContract,
        referenceSnapshotId: reviewReferenceSnapshotId,
        rulesetVersion: REVIEW_RULESET_VERSION,
        issues: [{ severity: 'ERROR', rule: 'REVIEW-INFRA', message: failureMessage }], audit,
        progress: { phase: 'COMPLETE', message: failureMessage, lastHeartbeatAt: audit.completedAt, sequence: 1, errorCount: 1 },
        startedAt: reviewStartedAt, completedAt: audit.completedAt, updatedAt: audit.completedAt,
      }).catch((storeError) => console.error('[/review-plan] failed to persist mock review failure:', storeError));
      sendSSE({ type: 'error', code: 'REVIEW_INFRA_ERROR', message: failureMessage, audit });
      if (!res.destroyed && !res.writableEnded) res.end();
    }
    return;
  }

  const reviewController = new AbortController();
  activeReviewControllers.set(reviewId, reviewController);
  const reviewTimeout = setTimeout(() => reviewController.abort(new Error('Review request timed out')), LLM_LONG_REQUEST_TIMEOUT_MS);
  const finishReviewLifetime = () => {
    clearTimeout(reviewTimeout);
    activeReviewControllers.delete(reviewId);
  };

  try {
    // Contract-driven review loop: deterministic validation -> semantic review -> typed repair -> revalidation.
    type Fix = { section: string; newContent: string };
    type ReviewIssue = { severity: 'ERROR' | 'WARN'; rule: string; message: string };
    type AppliedChangeLog = { what: string; why: string; before: string; after: string };
    type ReviewData = { passes: boolean; issues: ReviewIssue[]; repairs: PlanRepairOperation[]; fixes: Fix[]; fixedContent?: string; changeLog: AppliedChangeLog[] };
    type ReviewLogEntry = { round: number; action: string; errorCount: number; message: string };

    let currentContent = planContent;
    let lastData: ReviewData = { passes: false, issues: [], repairs: [], fixes: [], fixedContent: planContent, changeLog: [] };
    const reviewLog: ReviewLogEntry[] = [];
    const accumulatedChangeLog: AppliedChangeLog[] = [];
    const round = 1;
    const initialCanonicalContract = queuedCanonicalContract;

    sendProgress('preparing', 'Loading review rules and reference project...', round);
    const lastReferenceEvidence = parentReviewRecord
      ? { adaptationSummary: await getReferenceAdaptationSummary(), search: null, searchStatus: 'SUCCESS' as const, searchDurationMs: 0 }
      : await getReferenceReviewEvidence(buildCanonicalReferenceIntent(initialCanonicalContract));
    reviewReferenceSnapshotId ??= lastReferenceEvidence.search?.snapshotId;
    if (!parentReviewRecord && (lastReferenceEvidence.searchStatus !== 'SUCCESS' || !lastReferenceEvidence.search)) {
      throw new ReviewInfrastructureError(
        `Reference evidence unavailable: ${lastReferenceEvidence.searchStatus ?? 'INVALID_RESPONSE'}${lastReferenceEvidence.searchDiagnostic ? ` (${lastReferenceEvidence.searchDiagnostic})` : ''}`,
        buildReviewAudit(reviewId, reviewStartedAt, reviewRoundEvidence),
      );
    }
    const reusable = reviewStore.findReusable({
      projectId, subjectId, stage, contentHash: requestContentHash, contractHash: requestContractHash,
      subjectDescriptorHash, rulesetVersion: REVIEW_RULESET_VERSION,
      referenceSnapshotId: reviewReferenceSnapshotId, excludeReviewId: reviewId,
    });
    if (reusable?.result) {
      const audit = buildReviewAudit(reviewId, reviewStartedAt, []);
      const reusableStatus = reusable.result.finalStatus
        ?? (reusable.status === 'PASSED' || reusable.status === 'PASSED_WITH_WARNINGS' || reusable.status === 'BLOCKED'
          ? reusable.status : undefined);
      if (!reusableStatus) throw new Error('Reusable review record is missing its original final status.');
      const cachedResult = { ...reusable.result, finalStatus: reusableStatus, cacheHit: true };
      await reviewStore.save({
        ...reusable,
        reviewId,
        status: reusableStatus,
        audit,
        progress: { phase: 'COMPLETE', message: 'Reused identical reviewed result.', lastHeartbeatAt: audit.completedAt, sequence: 1 },
        result: cachedResult,
        startedAt: reviewStartedAt,
        completedAt: audit.completedAt,
        updatedAt: audit.completedAt,
      });
      sendProgress('complete', '相同内容、契约和参考快照已审核，直接复用已有结果。', 1,
        { errorCount: reusable.issues.filter((issue) => issue.severity === 'ERROR').length,
          warningCount: reusable.issues.filter((issue) => issue.severity === 'WARN').length });
      sendSSE({ type: 'done', data: {
        reviewId, contractHash: reusable.contractHash, status: reusableStatus,
        passes: cachedResult.passes, issues: reusable.issues, fixedContent: cachedResult.fixedContent,
        reviewLog: cachedResult.reviewLog, changeLog: cachedResult.changeLog, audit, cacheHit: true,
      } });
      finishReviewLifetime();
      res.end();
      return;
    }
    const refSummary = formatReferenceReviewEvidenceForSemanticReview(lastReferenceEvidence);
    let sourceCompilation = compilePlanContract(currentContent);
    currentContent = upsertPlanContractBlock(currentContent, sourceCompilation.contract);
    let canonicalCompilation = compilePlanContract(currentContent);
    let compilationForReview = {
      ...canonicalCompilation,
      source: sourceCompilation.source,
      diagnostics: sourceCompilation.diagnostics,
    };
    let deterministicIssues = validatePlanContract(compilationForReview, lastReferenceEvidence, currentContent);

    // Phase 1: apply only mechanically provable repairs before semantic review.
    const mechanicalRepairs = planSafeDeterministicRepairs(
      canonicalCompilation.contract, deterministicIssues, lastReferenceEvidence);
    if (mechanicalRepairs.length > 0) {
      const mechanicalResult = applyPlanRepairOperations(canonicalCompilation.contract, mechanicalRepairs);
      if (mechanicalResult.applied.length > 0 && mechanicalResult.rejected.length === 0) {
        const mechanicallyRepaired = upsertPlanContractBlock(currentContent, mechanicalResult.contract);
        const repairedCompilation = compilePlanContract(mechanicallyRepaired);
        const repairedIssues = validatePlanContract(repairedCompilation, lastReferenceEvidence, mechanicallyRepaired);
        const beforeErrors = deterministicIssues.filter((issue) => issue.severity === 'ERROR').length;
        const afterErrors = repairedIssues.filter((issue) => issue.severity === 'ERROR').length;
        const previousRules = new Set(deterministicIssues.filter((issue) => issue.severity === 'ERROR').map((issue) => issue.rule));
        const introducedRules = repairedIssues.filter((issue) => issue.severity === 'ERROR' && !previousRules.has(issue.rule));
        if (afterErrors < beforeErrors && introducedRules.length === 0) {
          currentContent = mechanicallyRepaired;
          sourceCompilation = compilePlanContract(currentContent);
          canonicalCompilation = sourceCompilation;
          compilationForReview = sourceCompilation;
          deterministicIssues = repairedIssues;
          const entries = mechanicalResult.applied.map((repair) => ({
            what: repair.operation,
            why: repair.resolves.join(', ') || 'mechanically provable repair',
            before: repair.targetId ?? '',
            after: JSON.stringify(repair.arguments),
          }));
          accumulatedChangeLog.push(...entries);
          reviewLog.push({ round, action: 'mechanical-repair', errorCount: afterErrors,
            message: `Applied ${mechanicalResult.applied.length} deterministic repairs before semantic review; ERROR ${beforeErrors} -> ${afterErrors}` });
          sendProgress('fixing', reviewLog[reviewLog.length - 1].message, round, { errorCount: afterErrors });
        }
      }
    }

    const deterministicErrorCount = deterministicIssues.filter((issue) => issue.severity === 'ERROR').length;
    const deterministicWarningCount = deterministicIssues.filter((issue) => issue.severity === 'WARN').length;
    const deterministicContext = formatDeterministicIssues(deterministicIssues);
    sendProgress('reviewing', 'Deterministic validation completed; one semantic review is running...', round, {
      errorCount: deterministicErrorCount,
      warningCount: deterministicWarningCount,
    });
    let waitingSeconds = 0;
    const text = await awaitWithHeartbeat(
      callAnthropicJson(
        withReferenceSummary(
          deterministicErrorCount === 0
            ? buildFastSemanticReviewSystemPrompt(stage || 'master')
            : buildReviewPlanSystemPrompt(stage || 'master'),
          refSummary,
        ) + `

${deterministicContext}`,
        `Review the plan below. The machine-readable plan-contract is the source of truth. All structural fixes must use typed repairs first:

${buildSemanticReviewSubject(currentContent, canonicalCompilation.contract)}`,
        { timeoutMs: LLM_LONG_REQUEST_TIMEOUT_MS, signal: reviewController.signal },
      ),
      () => {
        waitingSeconds += Math.round(REVIEW_HEARTBEAT_MS / 1000);
        sendProgress('reviewing', `Semantic review is still processing (${waitingSeconds}s elapsed)...`, round);
      },
    );
    const parsed = await parseReviewModelResponseWithRecovery(text, {
      round,
      referenceSummaryAvailable: Boolean(refSummary),
      referenceSnapshotId: reviewReferenceSnapshotId,
      contractSource: sourceCompilation.source,
      contractSourceHash: canonicalCompilation.contract.sourceHash,
      deterministicErrorCount,
      deterministicWarningCount,
    }, async (invalidRaw, protocolError) => {
      sendProgress('reviewing', 'Response protocol invalid; attempting one audited schema recovery...', round);
      return callAnthropicJson(
        buildReviewSchemaRecoverySystemPrompt(),
        `Protocol error: ${protocolError}\n\nMalformed response to repair:\n${invalidRaw}`,
        { timeoutMs: LLM_LONG_REQUEST_TIMEOUT_MS, signal: reviewController.signal, maxTokens: 6_000 },
      );
    });
    reviewRoundEvidence.push(...parsed.evidence);
    if (!parsed.ok) {
      throw new ReviewInfrastructureError(
        `Semantic review returned an invalid response: ${parsed.error}`,
        buildReviewAudit(reviewId, reviewStartedAt, reviewRoundEvidence),
      );
    }

    const semanticGate = gateSemanticIssues(parsed.value.issues, deterministicIssues,
      canonicalCompilation.contract, lastReferenceEvidence);
    const data: ReviewData = { ...parsed.value, issues: semanticGate.issues };
    if (semanticGate.downgradedErrorCount > 0) {
      reviewLog.push({ round, action: 'semantic-claim-gate', errorCount: deterministicErrorCount,
        message: `Downgraded ${semanticGate.downgradedErrorCount} unverified semantic ERROR claim(s) to advisory warnings.` });
    }
    const blockingRules = new Set([
      ...deterministicIssues.filter((issue) => issue.severity === 'ERROR').map((issue) => normalizeRule(issue.rule)),
      ...data.issues.filter((issue) => issue.severity === 'ERROR').map((issue) => normalizeRule(issue.rule)),
    ]);
    const plannedRepairs = filterAutomaticRepairOperations(data.repairs, lastReferenceEvidence, canonicalCompilation.contract)
      .filter((repair) => repair.resolves.some((rule) => blockingRules.has(normalizeRule(rule))));
    let candidateContent = currentContent;
    let appliedRepairs: PlanRepairOperation[] = [];
    if (plannedRepairs.length > 0) {
      const repairResult = applyPlanRepairOperations(canonicalCompilation.contract, plannedRepairs);
      appliedRepairs = repairResult.applied;
      if (repairResult.applied.length > 0) {
        candidateContent = upsertPlanContractBlock(candidateContent, repairResult.contract);
      }
      if (repairResult.rejected.length > 0) {
        reviewLog.push({ round, action: 'repair-rejected', errorCount: deterministicErrorCount,
          message: `Rejected typed repairs: ${repairResult.rejected.map((entry) => entry.reason).join('; ')}` });
      }
    }
    if (data.fixes.length > 0) {
      const sectionResult = applyFixes(candidateContent, data.fixes);
      candidateContent = sectionResult.result;
      const repairedContract = compilePlanContract(candidateContent).contract;
      candidateContent = upsertPlanContractBlock(candidateContent, repairedContract);
      reviewLog.push({ round, action: 'repair-narrative', errorCount: deterministicErrorCount,
        message: `Updated ${sectionResult.applied} narrative sections; skipped ${sectionResult.skipped}` });
    }

    const candidateCompilation = compilePlanContract(candidateContent);
    candidateContent = upsertPlanContractBlock(candidateContent, candidateCompilation.contract);
    const candidateDeterministicIssues = validatePlanContract(
      compilePlanContract(candidateContent), lastReferenceEvidence, candidateContent);
    const currentRules = new Set(deterministicIssues.filter((issue) => issue.severity === 'ERROR').map((issue) => issue.rule));
    const introducedRules = candidateDeterministicIssues
      .filter((issue) => issue.severity === 'ERROR' && !currentRules.has(issue.rule));
    const candidateErrorCount = candidateDeterministicIssues.filter((issue) => issue.severity === 'ERROR').length;
    if (introducedRules.length > 0 || candidateErrorCount > deterministicErrorCount) {
      appliedRepairs = [];
      candidateContent = currentContent;
      reviewLog.push({ round, action: 'repair-rejected', errorCount: deterministicErrorCount,
        message: `Candidate repair failed the non-regression gate; ERROR ${deterministicErrorCount} -> ${candidateErrorCount}` });
    } else {
      currentContent = candidateContent;
    }

    // A single semantic review cannot prove that its own semantic ERROR was fixed.
    // Typed repairs may improve the persisted content, but semantic blockers remain until a fresh review reruns.
    const unresolvedSemanticIssues = data.issues;
    const generatedChangeLog = appliedRepairs.map((repair) => ({
      what: repair.operation,
      why: repair.resolves.join(', ') || 'typed contract repair',
      before: repair.targetId ?? '',
      after: JSON.stringify(repair.arguments),
    }));
    accumulatedChangeLog.push(...data.changeLog, ...generatedChangeLog);
    lastData = {
      passes: false,
      issues: unresolvedSemanticIssues,
      repairs: appliedRepairs,
      fixes: data.fixes,
      fixedContent: currentContent,
      changeLog: accumulatedChangeLog,
    };
    const postReviewIssues = mergeReviewIssues(
      validatePlanContract(compilePlanContract(currentContent), lastReferenceEvidence, currentContent),
      unresolvedSemanticIssues,
    );
    const errorCount = postReviewIssues.filter((issue) => issue.severity === 'ERROR').length;
    const warningCount = postReviewIssues.filter((issue) => issue.severity === 'WARN').length;
    lastData.passes = errorCount === 0;
    reviewLog.push({ round, action: appliedRepairs.length > 0 ? 'semantic-repair' : 'semantic-review', errorCount,
      message: `Semantic review completed; ${appliedRepairs.length} typed repairs applied, ${errorCount} ERROR remain` });
    sendProgress('analyzing', `Semantic review completed: ${errorCount} ERROR, ${warningCount} WARN`, round,
      { errorCount, warningCount });
    sendProgress('complete', errorCount === 0 ? 'Review passed' : 'Review blocked', round,
      { errorCount, warningCount });

    let finalCompilation = compilePlanContract(currentContent);
    let finalContract = withContractReviewMetadata(finalCompilation.contract, {
      referenceSnapshotId: reviewReferenceSnapshotId,
      rulesetVersion: REVIEW_RULESET_VERSION,
    });
    currentContent = renderCanonicalContractSummary(currentContent, finalContract);
    finalCompilation = compilePlanContract(currentContent);
    finalContract = withContractReviewMetadata(finalCompilation.contract, {
      referenceSnapshotId: reviewReferenceSnapshotId,
      rulesetVersion: REVIEW_RULESET_VERSION,
    });
    currentContent = upsertPlanContractBlock(currentContent, finalContract);
    const finalDeterministicIssues = validatePlanContract(
      compilePlanContract(currentContent), lastReferenceEvidence, currentContent);
    const consistencyIssues = validateNarrativeContractConsistency(currentContent, finalContract);
    const finalIssues = mergeReviewIssues([...finalDeterministicIssues, ...consistencyIssues], lastData.issues);
    const finalErrorCount = finalIssues.filter((issue) => issue.severity === 'ERROR').length;
    const finalWarningCount = finalIssues.filter((issue) => issue.severity === 'WARN').length;
    const finalPasses = finalErrorCount === 0;
    const finalStatus = finalPasses
      ? finalWarningCount > 0 ? 'PASSED_WITH_WARNINGS' : 'PASSED'
      : 'BLOCKED';
    const audit = buildReviewAudit(reviewId, reviewStartedAt, reviewRoundEvidence);
    const contractHash = hashPlanContract(finalContract);
    if (normalizedSubjectDescriptor) {
      subjectDescriptorHash = hashSubPlanDescriptor({ ...normalizedSubjectDescriptor,
        contentHash: hashPlanContent(currentContent), contractHash });
    }
    await reviewStore.save({
      reviewId, projectId, subjectId, stage: stage || 'master', status: finalStatus,
      contentHash: hashPlanContent(currentContent), contractHash, subjectDescriptorHash, contract: finalContract,
      referenceSnapshotId: reviewReferenceSnapshotId,
      rulesetVersion: REVIEW_RULESET_VERSION, issues: finalIssues, audit,
      progress: { phase: 'COMPLETE', message: finalPasses ? 'Review passed.' : 'Review blocked.',
        lastHeartbeatAt: audit.completedAt, sequence: 1, errorCount: finalErrorCount, warningCount: finalWarningCount },
      result: { finalStatus, passes: finalPasses, fixedContent: currentContent, reviewLog, changeLog: lastData.changeLog },
      startedAt: reviewStartedAt, completedAt: audit.completedAt, updatedAt: audit.completedAt,
    });
    sendSSE({
      type: 'done',
      data: {
        reviewId,
        contractHash,
        status: finalStatus,
        passes: finalPasses,
        issues: finalIssues,
        fixedContent: currentContent,
        reviewLog,
        changeLog: lastData.changeLog,
        audit,
      },
    });
    finishReviewLifetime();
    res.end();
  } catch (err) {
    finishReviewLifetime();
    const abortMessage = reviewController.signal.aborted
      ? (reviewController.signal.reason instanceof Error
        ? reviewController.signal.reason.message : String(reviewController.signal.reason ?? 'Review request aborted'))
      : '';
    const userCancelled = /cancelled by user/i.test(abortMessage);
    const requestTimedOut = /timed out/i.test(abortMessage);
    const failureCode = userCancelled ? 'REVIEW_CANCELLED' : requestTimedOut ? 'REVIEW_TIMEOUT' : 'REVIEW_INFRA_ERROR';
    const failureRule = userCancelled ? 'REVIEW-CANCELLED' : requestTimedOut ? 'REVIEW-TIMEOUT' : 'REVIEW-INFRA';
    const msg = userCancelled ? 'Review was cancelled by the user.'
      : requestTimedOut ? 'Review request timed out before a valid result was produced.'
        : err instanceof Error ? err.message : String(err);
    console.error('[/review-plan] review failed:', msg);
    const audit = err instanceof ReviewInfrastructureError
      ? err.audit
      : buildReviewAudit(reviewId, reviewStartedAt, reviewRoundEvidence);
    const failedCompilation = compilePlanContract(planContent || '');
    if (normalizedSubjectDescriptor) {
      subjectDescriptorHash = hashSubPlanDescriptor({ ...normalizedSubjectDescriptor,
        contentHash: hashPlanContent(planContent || ''), contractHash: hashPlanContract(failedCompilation.contract) });
    }
    await reviewStore.save({
      reviewId, projectId, subjectId, stage: stage || 'master', status: 'REVIEW_INFRA_ERROR',
      contentHash: hashPlanContent(planContent || ''), contractHash: hashPlanContract(failedCompilation.contract),
      subjectDescriptorHash, contract: failedCompilation.contract, rulesetVersion: REVIEW_RULESET_VERSION,
      issues: [{ severity: 'ERROR', rule: failureRule, message: msg }], audit,
      progress: { phase: 'COMPLETE', message: msg, lastHeartbeatAt: audit.completedAt, sequence: 1, errorCount: 1 },
      startedAt: reviewStartedAt, completedAt: audit.completedAt, updatedAt: audit.completedAt,
    }).catch((storeError) => console.error('[/review-plan] failed to persist review failure:', storeError));
    sendSSE({ type: 'error', code: failureCode, message: msg, audit });
    if (!res.destroyed && !res.writableEnded) res.end();
  }
});

// ==================== /enrich-requirement (非流式 JSON) ====================
// 根据用户当前的(短)需求 + 已配置的模块信息,反向生成一份完整、详细的需求描述。
// 主要供「帮我补齐需求」按钮使用 — 用户写了三个字"做个商品",一键展开成完整字段+校验+状态机。

llmRouter.post('/enrich-requirement', async (req: Request, res: Response) => {
  const { userInput, modules } = req.body as { userInput: string; modules?: ModuleSummary[] };

  if (!isLlmConfigured()) {
    // Mock 降级 — 拼一个简化的样板
    res.json({
      success: true,
      data: {
        enriched: `${userInput || '业务模块'}\n\n业务字段(示例):\n- name: 名称 (String, 必填)\n- code: 编码 (String, 必填, 唯一)\n- status: 状态 (Integer)\n- remark: 备注 (String)\n\n业务规则:\n- 编码全局唯一\n- 删除使用逻辑删除\n- 列表支持按名称/状态分页查询\n\n(LLM 未配置,以上为示例占位文本)`,
      },
    });
    return;
  }

  const moduleSummary = (modules || [])
    .map((m, i) => `${i + 1}. [${m.type}] ${m.name}  config=${JSON.stringify(m.config ?? {})}`)
    .join('\n') || '(无)';

  const sysPrompt = `你是一位资深产品经理 + BladeX 后端架构师。
任务: 根据用户输入的简短需求 + 已在画布上拖入的模块配置, 推断并展开为一份**完整、可执行**的业务需求描述。

输出格式: 纯文本 (不要 markdown 包裹, 不要解释步骤), 应包含:
1. 业务领域简介 (1-2 句)
2. 业务字段清单 (字段名/类型/是否必填/中文注释, 至少 6-10 个)
3. 业务状态机 (如订单状态/审批状态等, 如适用)
4. 关键业务规则 (唯一约束、删除策略、列表筛选条件等)
5. 是否需要 Excel 导入导出 / Feign 远程调用 等辅助功能

要求:
- 字段名用 camelCase, 类型用 Java 类型 (String/Long/Integer/BigDecimal/Boolean/Date/LocalDateTime)
- 严格遵守用户已配置的模块约束 (表名/实体名/模块名), 不要替换
- 若用户配置了 fields, 必须保留且可补充更多字段
- 文本控制在 800 字以内, 简洁专业`;

  const userPrompt = `用户简短需求:\n${userInput || '(空)'}\n\n已配置模块:\n${moduleSummary}\n\n请展开为完整业务需求描述。`;

  try {
    const text = await callAnthropicJson(sysPrompt, userPrompt);
    res.json({ success: true, data: { enriched: text.trim() } });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/enrich-requirement] LLM 调用失败:', msg);
    res.status(502).json({ success: false, error: msg });
  }
});

// ==================== /suggest-modules (非流式 JSON) ====================
// 根据需求文本 反向建议要拖入哪些模块类型(ENTITY/API/EXCEL/FEIGN 等),以及推荐的命名。
// 供「推荐拖入模块」按钮使用,识别业务领域后告诉用户该拖什么。

llmRouter.post('/suggest-modules', async (req: Request, res: Response) => {
  const { userInput } = req.body as { userInput: string };

  if (!isLlmConfigured()) {
    res.json({
      success: true,
      data: {
        suggestions: [
          { type: 'ENTITY', name: '数据模型', icon: '📦', config: { tableName: 'blade_xxx', moduleName: 'xxx', entityName: 'Xxx', needVO: true, needExcel: true } },
          { type: 'API', name: 'API接口', icon: '🔌', config: { pathPrefix: 'xxx', needAuth: true } },
        ],
        reasoning: '(LLM 未配置, 返回通用模板建议)',
      },
    });
    return;
  }

  const sysPrompt = `你是一位资深 BladeX 架构师。
任务: 根据用户输入的业务需求,识别业务领域,推断该用户需要拖入哪些 BladeX 模块,以及推荐的命名。

可用模块类型: ENTITY (数据模型) / API (API接口) / EXCEL (Excel导入导出) / FEIGN (远程调用) / PAGE (前端页面) / FLOW (工作流) / JOB (定时任务) / CONFIG (Nacos配置)

只输出 JSON, 不要 markdown 包裹, 不要解释, 结构:
{
  "suggestions": [
    {
      "type": "ENTITY",
      "name": "数据模型",
      "icon": "📦",
      "config": { "tableName": "blade_xxx", "moduleName": "xxx", "entityName": "Xxx", "needVO": true, "needExcel": true }
    }
  ],
  "reasoning": "为什么推荐这些模块的一句话说明"
}

要求:
- type 必须是上述 8 种之一
- ENTITY 必须配 tableName/moduleName/entityName, 命名用业务领域常识 (订单→blade_order/Order/order, 商品→blade_product/Product/product)
- ENTITY 若涉及报表/数据导出,设置 needExcel:true
- 业务核心 CRUD 一般需要 ENTITY+API, 如有跨服务调用建议补 FEIGN
- 简单业务 2-3 个模块即可, 不要过度推荐`;

  const userPrompt = `用户需求: ${userInput || '(空)'}\n\n请返回推荐模块清单 JSON。`;

  try {
    const text = await callAnthropicJson(sysPrompt, userPrompt);
    const parsed = safeJsonParse(text);
    if (!parsed || !Array.isArray(parsed.suggestions)) {
      res.json({
        success: true,
        data: { suggestions: [], reasoning: '未能解析 LLM 响应,请直接手动拖入模块' },
      });
      return;
    }
    res.json({ success: true, data: parsed });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/suggest-modules] LLM 调用失败:', msg);
    res.status(502).json({ success: false, error: msg });
  }
});

// ==================== /complete-one-shot (一键完备) ====================
// 串行调用 suggest-modules → enrich-requirement, 解决"先推荐还是先补齐"的不确定性。
// 用户点一个按钮就把"模块推荐 + 需求补齐"两件事按确定顺序做完。

llmRouter.post('/complete-one-shot', async (req: Request, res: Response) => {
  const { userInput, existingModules } = req.body as {
    userInput: string;
    existingModules?: ModuleSummary[];
  };

  if (!userInput || userInput.trim().length < 2) {
    res.status(400).json({ success: false, error: '需求文本太短(至少 2 字)' });
    return;
  }

  if (!isLlmConfigured()) {
    // Mock 降级
    res.json({
      success: true,
      data: {
        suggestions: [
          { type: 'ENTITY', name: '数据模型', icon: '📦', config: { tableName: 'blade_xxx', moduleName: 'xxx', entityName: 'Xxx', needVO: true, needExcel: true } },
        ],
        enriched: `${userInput}\n\n(Mock: LLM 未配置, 一键完备退化为示例)\n\n业务字段:\n- name: 名称\n- code: 编码\n- status: 状态`,
        reasoning: 'Mock 模式',
      },
    });
    return;
  }

  try {
    // ── 第一步: 推荐模块 ─────────────────────────────
    const suggestPrompt = `你是 BladeX 架构师。根据用户需求识别业务领域,推荐应当拖入的模块。
可用模块类型: ENTITY/API/EXCEL/FEIGN/PAGE/FLOW/JOB/CONFIG
只输出 JSON, 不要 markdown 包裹, 不要解释:
{"suggestions":[{"type":"ENTITY","name":"数据模型","icon":"📦","config":{"tableName":"blade_xxx","moduleName":"xxx","entityName":"Xxx","needVO":true,"needExcel":true}}],"reasoning":"简短理由"}
要求:
- ENTITY 必须配 tableName/moduleName/entityName, 用业务领域常识命名 (订单→blade_order/Order/order)
- 涉及导出报表配 needExcel:true
- 2-3 个模块即可, 不要过度推荐
- 排除已有模块类型 (避免重复)`;

    const existingTypes = (existingModules || []).map((m) => m.type);
    const suggestUserPrompt = `用户需求: ${userInput.trim()}\n已有模块类型: ${existingTypes.length ? existingTypes.join(', ') : '(无)'}`;

    const suggestText = await callAnthropicJson(suggestPrompt, suggestUserPrompt);
    const suggestParsed = safeJsonParse(suggestText) as
      | { suggestions?: unknown[]; reasoning?: string }
      | null;
    const suggestions = normalizeOneShotSuggestions(Array.isArray(suggestParsed?.suggestions) ? suggestParsed.suggestions : []);
    const reasoning = (suggestParsed?.reasoning as string) || '';

    // ── 第二步: 基于"原需求 + 已有模块 + 新推荐模块"展开需求 ──
    const allModules = [...(existingModules || []), ...(suggestions as ModuleSummary[])];
    const moduleSummary = allModules
      .map((m, i) => `${i + 1}. [${m.type}] ${m.name}  config=${JSON.stringify(m.config ?? {})}`)
      .join('\n') || '(无)';

    const enrichPrompt = `你是产品经理 + BladeX 架构师。根据用户简短需求和已配置模块,生成一份完整业务需求描述。
输出: 纯文本(非 markdown), 含:
1. 业务领域简介(1-2 句)
2. 业务字段清单(每个字段必须独占一行, 格式严格为: fieldName / JavaType / 必填|非必填 / description, 至少 6-10 个)
3. 业务状态机(如适用)
4. 关键业务规则
5. 是否需要 Excel/Feign 等辅助
要求:
- 字段名 camelCase, 类型用 Java 类型
- 严格遵守模块约束 (表名/实体名/模块名), 不要替换
- 800 字以内, 简洁专业`;

    const enrichUserPrompt = `用户简短需求: ${userInput.trim()}\n\n已配置/已推荐模块:\n${moduleSummary}\n\n请展开为完整业务需求描述。`;

    const enrichText = await callAnthropicJson(enrichPrompt, enrichUserPrompt);

    res.json({
      success: true,
      data: {
        suggestions,
        enriched: enrichText.trim(),
        reasoning,
      },
    });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/complete-one-shot] LLM 调用失败:', msg);
    res.status(502).json({ success: false, error: msg });
  }
});

// ==================== /split-plan (非流式 JSON) ====================

llmRouter.post('/split-plan', async (req: Request, res: Response) => {
  const { planContent, reviewId, projectId, subjectId } = req.body as {
    planContent: string; reviewId?: string; projectId?: string; subjectId?: string;
  };

  const reviewRecord = reviewId ? reviewStore.get(reviewId) : undefined;
  if (!reviewRecord || !reviewStore.isCurrent(reviewId!)) {
    res.status(428).json({ success: false, code: 'REVIEW_REQUIRED', error: 'A current persisted successful master-plan review is required before splitting' });
    return;
  }
  if (!projectId?.trim() || !subjectId?.trim()
    || reviewRecord.projectId !== projectId.trim() || reviewRecord.subjectId !== subjectId.trim()) {
    res.status(428).json({ success: false, code: 'REVIEW_REQUIRED', error: 'Review evidence does not belong to the requested project and master plan' });
    return;
  }
  if (reviewRecord.stage !== 'master' || (reviewRecord.status !== 'PASSED' && reviewRecord.status !== 'PASSED_WITH_WARNINGS')) {
    res.status(422).json({ success: false, code: 'REVIEW_BLOCKED', error: `Review ${reviewId} is not a successful master-plan review` });
    return;
  }
  const currentCompilationForReview = compilePlanContract(planContent);
  if (reviewRecord.contentHash !== hashPlanContent(planContent)
    || reviewRecord.contractHash !== hashPlanContract(currentCompilationForReview.contract)) {
    res.status(412).json({ success: false, code: 'REVIEW_STALE', error: 'Plan content or canonical contract changed after review' });
    return;
  }

  if (!isLlmConfigured()) {
    res.json({ success: true, data: mockSplit(planContent) });
    return;
  }

  try {
    const referenceEvidence = await getReferenceReviewEvidence(stripPlanContractBlock(planContent));
    const refSummary = formatReferenceReviewEvidence(referenceEvidence);
    const initialCompilation = compilePlanContract(planContent);
    const canonicalPlanContent = upsertPlanContractBlock(planContent, initialCompilation.contract);
    const canonicalCompilation = compilePlanContract(canonicalPlanContent);
    const deterministicIssues = validatePlanContract(canonicalCompilation, referenceEvidence, canonicalPlanContent);
    const blockingIssues = deterministicIssues.filter((issue) => issue.severity === 'ERROR');
    if (blockingIssues.length > 0) {
      res.status(422).json({
        success: false,
        code: 'SPLIT_BLOCKED',
        error: `Plan has ${blockingIssues.length} deterministic ERROR and cannot be split`,
        issues: blockingIssues,
        referenceSnapshotId: referenceEvidence.search?.snapshotId,
      });
      return;
    }

    const text = await callAnthropicJson(
      withReferenceSummary(buildSplitPlanSystemPrompt(), refSummary),
      `Split this reviewed plan from its canonical plan-contract. Do not invent modules, identities or deliverables:\n\n${canonicalPlanContent}`,
      { timeoutMs: LLM_LONG_REQUEST_TIMEOUT_MS });
    const recovery = await parseSplitModelResponseWithRecovery(
      text,
      canonicalCompilation.contract,
      (protocolError, malformedResponse) => callAnthropicJson(
        withReferenceSummary(buildSplitSchemaRecoverySystemPrompt(), refSummary),
        `Protocol error: ${protocolError}

Canonical plan (authoritative):
${canonicalPlanContent}

Malformed split response:
${malformedResponse}`,
        { timeoutMs: LLM_LONG_REQUEST_TIMEOUT_MS, maxTokens: 8_000 },
      ),
      subjectId.trim(),
    );
    if (!recovery.parsed.ok) {
      res.status(502).json({
        success: false,
        code: 'SPLIT_INFRA_ERROR',
        error: recovery.parsed.error,
        schemaRecoveryAttempted: recovery.schemaRecovered,
        referenceSnapshotId: referenceEvidence.search?.snapshotId,
      });
      return;
    }
    res.json({
      success: true,
      data: recovery.parsed.value,
      schemaRecovered: recovery.schemaRecovered,
      contractVersion: canonicalCompilation.contract.contractVersion,
      referenceSnapshotId: referenceEvidence.search?.snapshotId,
      reviewId,
      contractHash: hashPlanContract(canonicalCompilation.contract),
    });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error('[/split-plan] LLM 调用失败:', msg);
    res.status(502).json({ success: false, error: msg });
  }
});

// ==================== Live LLM 实现 ====================

/** 拼接参考项目摘要到 systemPrompt(无摘要则原样返回) */
function withReferenceSummary(basePrompt: string, refSummary: string | null): string {
  if (!refSummary) return basePrompt;
  return basePrompt + '\n\n== 参考项目适配(新模块必须遵循, 以接入参考项目编译) ==\n' + refSummary;
}

/** 按 ## 章节标题替换方案内容。标题没匹配上的 fix 跳过(不破坏原方案)。 */
export function mergeReviewIssues(
  deterministic: DeterministicPlanIssue[],
  modelIssues: Array<{ severity: 'ERROR' | 'WARN'; rule: string; message: string }>,
): Array<{ severity: 'ERROR' | 'WARN'; rule: string; message: string }> {
  const merged = new Map<string, { severity: 'ERROR' | 'WARN'; rule: string; message: string }>();
  for (const issue of [...deterministic, ...modelIssues]) {
    const key = `${issue.rule}|${issue.message}`;
    const existing = merged.get(key);
    if (!existing || (existing.severity === 'WARN' && issue.severity === 'ERROR')) {
      merged.set(key, { severity: issue.severity, rule: issue.rule, message: issue.message });
    }
  }
  return Array.from(merged.values());
}

function filterAutomaticRepairOperations(
  repairs: PlanRepairOperation[],
  referenceEvidence: Awaited<ReturnType<typeof getReferenceReviewEvidence>>,
  contract: ReturnType<typeof compilePlanContract>['contract'],
): PlanRepairOperation[] {
  const unsafe = new Set(['ADD_MODULE', 'MOVE_ENTITY', 'SPLIT_AGGREGATE', 'DECLARE_ARCHITECTURE_DECISION',
    'RENAME_ENTITY', 'ADD_DELIVERABLE', 'CHANGE_STATE_OWNER', 'NORMALIZE_DELIVERABLE_TOPOLOGY']);
  const decision = referenceEvidence.search?.decisions[0];
  const stringArgument = (repair: PlanRepairOperation, name: string): string | undefined => {
    const value = repair.arguments[name];
    return typeof value === 'string' && value.trim() ? value.trim() : undefined;
  };
  return repairs.filter((repair) => {
    if (unsafe.has(repair.operation)) return false;
    if (repair.operation === 'ADD_INTEGRATION') {
      const targetModule = stringArgument(repair, 'targetModule');
      const sourceModule = stringArgument(repair, 'sourceModule');
      const entrypoint = stringArgument(repair, 'entrypoint');
      const evidenceClasses = decision?.evidenceSymbols.map((symbol) => symbol.slice(symbol.lastIndexOf('.') + 1)) ?? [];
      return Boolean(decision && decision.decision !== 'ARCHITECTURE_DECISION_REQUIRED'
        && decision.confidence >= 0.9 && decision.targetModule === targetModule
        && (!sourceModule || sourceModule === contract.identity.moduleName)
        && entrypoint && evidenceClasses.some((className) => entrypoint.startsWith(`${className}.`)));
    }
    if (repair.operation === 'ADD_REFERENCE_BINDING' || repair.operation === 'BIND_EXISTING_SYMBOL') {
      const referenceSymbol = stringArgument(repair, 'referenceSymbol');
      const targetModule = stringArgument(repair, 'targetModule');
      const requestedDecision = stringArgument(repair, 'decision');
      return Boolean(decision && (decision.decision === 'REUSE' || decision.decision === 'EXTEND')
        && decision.confidence >= 0.9 && referenceSymbol
        && decision.evidenceSymbols.includes(referenceSymbol)
        && requestedDecision === decision.decision
        && (!targetModule || targetModule === decision.targetModule));
    }
    return false;
  });
}

function applyFixes(plan: string, fixes: { section: string; newContent: string }[]): { result: string; applied: number; skipped: number } {
  let result = plan;
  let applied = 0;
  let skipped = 0;
  for (const fix of fixes) {
    const section = fix.section.trim();
    const start = result.indexOf(section);
    if (start < 0) {
      console.warn(`[/review-plan] 章节未匹配, 跳过: ${section.substring(0, 40)}`);
      skipped++;
      continue;
    }
    // 找下一个 ## 标题(章节结束位置)
    const nextSection = result.indexOf('\n## ', start + section.length);
    const end = nextSection >= 0 ? nextSection : result.length;
    result = result.substring(0, start) + fix.newContent + result.substring(end);
    applied++;
  }
  return { result, applied, skipped };
}

class ReviewInfrastructureError extends Error {
  constructor(message: string, public readonly audit: ReviewAuditEvidence) {
    super(message);
    this.name = 'ReviewInfrastructureError';
  }
}

function normalizeReviewSubjectDescriptor(
  value: unknown,
  planContent: string,
  subjectId: string,
): { ok: true; value: SubPlanDescriptorHashMaterial } | { ok: false; error: string } {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, error: 'A canonical sub-plan descriptor is required for sub-plan review.' };
  }
  const row = value as Record<string, unknown>;
  const arrays = ['prerequisites', 'deliverableIds', 'referencedElementIds', 'inputTypes', 'outputTypes'] as const;
  if (row.id !== subjectId || !Number.isInteger(row.index) || Number(row.index) < 1
    || typeof row.title !== 'string' || !row.title.trim()
    || typeof row.contractHash !== 'string' || !/^[a-f0-9]{64}$/.test(row.contractHash)
    || arrays.some((key) => !Array.isArray(row[key])
      || !(row[key] as unknown[]).every((item) => typeof item === 'string' && Boolean(item.trim())))) {
    return { ok: false, error: 'Sub-plan descriptor fields are invalid or do not match the reviewed subject.' };
  }
  return {
    ok: true,
    value: {
      id: subjectId,
      index: Number(row.index),
      title: row.title.trim(),
      contentHash: hashPlanContent(planContent),
      prerequisites: [...row.prerequisites as string[]],
      deliverableIds: [...row.deliverableIds as string[]],
      contractHash: row.contractHash,
      referencedElementIds: [...row.referencedElementIds as string[]],
      inputTypes: [...row.inputTypes as string[]],
      outputTypes: [...row.outputTypes as string[]],
    },
  };
}

function buildReviewAudit(
  reviewId: string,
  startedAt: string,
  rounds: ReviewRoundEvidence[],
): ReviewAuditEvidence {
  return {
    reviewId,
    rulesetVersion: REVIEW_RULESET_VERSION,
    startedAt,
    completedAt: new Date().toISOString(),
    rounds: rounds.map((round) => ({ ...round })),
  };
}

/** Emits periodic progress while a non-streaming upstream request is pending. */
export async function awaitWithHeartbeat<T>(
  operation: Promise<T>,
  onHeartbeat: () => void,
  intervalMs = REVIEW_HEARTBEAT_MS,
): Promise<T> {
  const timer = setInterval(onHeartbeat, intervalMs);
  timer.unref?.();
  try {
    return await operation;
  } finally {
    clearInterval(timer);
  }
}

/** Calls the non-streaming Anthropic Messages API and returns the first text block. */
interface AnthropicCallOptions {
  timeoutMs?: number;
  signal?: AbortSignal;
  maxTokens?: number;
}

async function callAnthropicJson(
  systemPrompt: string,
  userPrompt: string,
  options: AnthropicCallOptions = {},
): Promise<string> {
  const cfg = getLlmConfig();
  const base = cfg.baseUrl.replace(/\/+$/, '');
  const controller = new AbortController();
  const timeoutMs = options.timeoutMs ?? LLM_DEFAULT_REQUEST_TIMEOUT_MS;
  const abortFromParent = () => controller.abort(options.signal?.reason);

  if (options.signal?.aborted) abortFromParent();
  else options.signal?.addEventListener('abort', abortFromParent, { once: true });

  const timer = setTimeout(() => controller.abort(new Error('LLM request timed out')), timeoutMs);
  try {
    const resp = await fetchWithTransientRetry(`${base}/v1/messages`, {
      method: 'POST',
      headers: buildAuthHeaders(),
      body: JSON.stringify({
        model: cfg.model,
        max_tokens: options.maxTokens ?? cfg.maxTokens,
        system: systemPrompt,
        messages: [{ role: 'user', content: userPrompt }],
      }),
      signal: controller.signal,
    });
    if (!resp.ok) {
      const errText = await resp.text().catch(() => '');
      throw new Error(`Anthropic ${resp.status}: ${errText.slice(0, 500)}`);
    }
    type AnthropicResp = { content?: { type: string; text?: string }[] };
    const data: AnthropicResp = await resp.json();
    const block = data.content?.find((c) => c.type === 'text');
    return block?.text || '';
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener('abort', abortFromParent);
  }
}

/** 流式调用 — Anthropic SSE → 转译为前端约定的 SSE 帧 */
async function handleLiveGeneratePlan(req: Request, res: Response): Promise<void> {
  const { userInput, modules } = req.body as { userInput: string; modules?: ModuleSummary[] };
  try {
    assertSingleConfiguredEntity(modules || []);
  } catch (error) {
    res.status(409).json({ success: false, code: 'PLAN_INPUT_CONFLICT', error: error instanceof Error ? error.message : String(error) });
    return;
  }
  const constraints: string[] = [];
  for (const module of modules || []) {
    const config = (module.config ?? {}) as Record<string, unknown>;
    if (module.type === 'ENTITY') {
      if (config.tableName) constraints.push(`tableName=${config.tableName}`);
      if (config.moduleName) constraints.push(`moduleName=${config.moduleName}`);
      if (config.entityName) constraints.push(`entityName=${config.entityName}`);
      const fields = config.fields as Array<{ name: string; type: string; comment?: string; nullable?: boolean }> | undefined;
      for (const field of fields || []) {
        constraints.push(`field ${field.name}:${field.type}, required=${field.nullable === false}, description=${field.comment || ''}`);
      }
      if (config.needVO) constraints.push('deliverable VO is required');
      if (config.needExcel) constraints.push('deliverable EXCEL is required');
    }
    if (module.type === 'API' && config.pathPrefix) constraints.push(`API path prefix=/${config.pathPrefix}`);
    if (module.type === 'FEIGN' && config.targetService) constraints.push(`Feign target service=${config.targetService}`);
  }
  const moduleSummary = (modules || [])
    .map((module, index) => `${index + 1}. [${module.type}] ${module.name} config=${JSON.stringify(module.config ?? {})}`)
    .join('\n') || '(none)';
  const userPrompt = `Requirement:\n${(userInput || '').trim() || '(empty)'}\n\nConfigured modules:\n${moduleSummary}\n\nImmutable constraints:\n${constraints.join('\n') || '(none)'}`;

  const controller = new AbortController();
  const finish = bindUpstreamAbort(res, controller, LLM_LONG_REQUEST_TIMEOUT_MS,
    (reason) => console.warn(`[/generate-plan] cancelled: ${reason}`));
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  res.flushHeaders();
  res.socket?.setNoDelay(true);
  const send = (data: object) => {
    if (!res.destroyed && !res.writableEnded) res.write(`data: ${JSON.stringify(data)}\n\n`);
  };
  send({ type: 'progress', stage: 'analyzing', message: '正在分析需求并加载参考项目证据...' });

  try {
    const referenceEvidence = await getReferenceReviewEvidence(userPrompt);
    const referenceSummary = formatReferenceReviewEvidenceForGeneration(referenceEvidence);
    const configuredDraft = compileConfiguredPlanDraft(userInput || '', modules || []);
    if (configuredDraft) {
      send({ type: 'progress', stage: 'planning', message: '正在根据已配置模块编译 Canonical Plan Contract v2...' });
      const normalizedDraft = normalizePlanDraftAgainstRequirement(configuredDraft, userInput || '');
      const referenceGrounded = groundPlanDraftWithReferenceEvidence(normalizedDraft, referenceEvidence);
      const contract = applyReferenceGrounding(
        compileStructuredPlanDraft(referenceGrounded.draft, referenceEvidence.search?.snapshotId),
        referenceGrounded.grounding,
      );
      const markdown = renderStructuredPlan(referenceGrounded.draft, contract);
      const compilation = compilePlanContract(markdown);
      const structuralErrors = validatePlanContract(compilation, referenceEvidence, markdown)
        .filter(isPlanDraftGenerationBlockingIssue);
      if (structuralErrors.length > 0) {
        throw new Error(`PLAN_DRAFT_VALIDATION_ERROR: ${structuralErrors.map((issue) => `${issue.rule}: ${issue.message}`).join('; ')}`);
      }
      for (let index = 0; index < markdown.length; index += 600) send({ type: 'content', chunk: markdown.slice(index, index + 600) });
      send({ type: 'complete', tokensUsed: 0, contractHash: hashPlanContract(compilation.contract), deterministic: true });
      return;
    }
    let waitingSeconds = 0;
    const raw = await awaitWithHeartbeat(callAnthropicJson(
      withReferenceSummary(buildGeneratePlanSystemPrompt(), referenceSummary),
      userPrompt,
      { timeoutMs: LLM_LONG_REQUEST_TIMEOUT_MS, signal: controller.signal, maxTokens: PLAN_DRAFT_MAX_TOKENS },
    ), () => {
      waitingSeconds += Math.round(REVIEW_HEARTBEAT_MS / 1000);
      send({ type: 'progress', stage: 'planning', message: `方案生成中 (${waitingSeconds}s)...` });
    });
    let parsed = parsePlanDraftResponse(raw);
    if (!parsed.ok) {
      send({ type: 'progress', stage: 'planning', message: '结构化草案不符合协议，正在执行一次 Schema 恢复...' });
      const recovered = await callAnthropicJson(
        withReferenceSummary(
          `${buildGeneratePlanSystemPrompt()}\nRepair the malformed draft into the exact schema. Preserve every immutable constraint and business field from the original input.`,
          referenceSummary,
        ),
        `Original generation input:\n${userPrompt}\n\nProtocol error: ${parsed.error}\n\nMalformed response:\n${raw}`,
        { timeoutMs: LLM_LONG_REQUEST_TIMEOUT_MS, signal: controller.signal, maxTokens: PLAN_DRAFT_MAX_TOKENS },
      );
      parsed = parsePlanDraftResponse(recovered);
    }
    if (!parsed.ok) throw new Error(`PLAN_DRAFT_PROTOCOL_ERROR: ${parsed.error}`);

    const normalizedDraft = normalizePlanDraftAgainstRequirement(parsed.value, userInput || '');
    const referenceGrounded = groundPlanDraftWithReferenceEvidence(normalizedDraft, referenceEvidence);
    const contract = applyReferenceGrounding(
      compileStructuredPlanDraft(referenceGrounded.draft, referenceEvidence.search?.snapshotId),
      referenceGrounded.grounding,
    );
    const markdown = renderStructuredPlan(referenceGrounded.draft, contract);
    const compilation = compilePlanContract(markdown);
    const issues = validatePlanContract(compilation, referenceEvidence, markdown);
    const structuralErrors = issues.filter(isPlanDraftGenerationBlockingIssue);
    if (structuralErrors.length > 0) {
      throw new Error(`PLAN_DRAFT_VALIDATION_ERROR: ${structuralErrors.map((issue) => `${issue.rule}: ${issue.message}`).join('; ')}`);
    }

    send({ type: 'progress', stage: 'planning', message: '结构化契约已通过校验，正在渲染方案正文...' });
    for (let index = 0; index < markdown.length; index += 600) {
      send({ type: 'content', chunk: markdown.slice(index, index + 600) });
    }
    send({ type: 'complete', tokensUsed: raw.length, contractHash: hashPlanContract(compilation.contract) });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error('[/generate-plan] failed:', message);
    send({ type: 'error', error: message });
  } finally {
    finish();
    if (!res.destroyed && !res.writableEnded) res.end();
  }
}

// ==================== Mock 实现 ====================

async function handleMockGeneratePlan(req: Request, res: Response): Promise<void> {
  const { userInput, modules } = req.body as { userInput: string; modules?: ModuleSummary[] };

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no');
  res.flushHeaders?.();

  let clientGone = false;
  req.on('close', () => { clientGone = true; });

  const entityModule = (modules || []).find((module) => module.type === 'ENTITY');
  const config = entityModule?.config && typeof entityModule.config === 'object' && !Array.isArray(entityModule.config)
    ? entityModule.config as Record<string, unknown> : {};
  const moduleName = typeof config.moduleName === 'string' && config.moduleName.trim()
    ? config.moduleName.trim().toLowerCase().replace(/^blade-/, '').replace(/-api$/, '') : 'order';
  const entityName = typeof config.entityName === 'string' && /^[A-Z][A-Za-z0-9]*$/.test(config.entityName.trim())
    ? config.entityName.trim() : 'Order';
  const tableName = typeof config.tableName === 'string' && config.tableName.trim()
    ? config.tableName.trim() : `blade_${moduleName.replace(/-/g, '_')}`;
  const configuredFields = Array.isArray(config.fields) ? config.fields.filter((field): field is Record<string, unknown> =>
    typeof field === 'object' && field !== null && !Array.isArray(field)) : [];
  const fields: PlanDraftV2['fields'] = configuredFields.length > 0
    ? configuredFields.map((field, index) => ({
      name: typeof field.name === 'string' && field.name.trim() ? field.name.trim() : `field${index + 1}`,
      columnName: typeof field.columnName === 'string' && field.columnName.trim()
        ? field.columnName.trim() : typeof field.name === 'string' ? field.name.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase() : `field_${index + 1}`,
      javaType: typeof field.javaType === 'string' && field.javaType.trim() ? field.javaType.trim() : 'String',
      required: field.required === true,
      role: field.role === 'DERIVED' ? 'DERIVED' : 'PERSISTENT',
      description: typeof field.description === 'string' && field.description.trim() ? field.description.trim() : 'Configured business field',
    }))
    : [
      { name: `${moduleName.replace(/-([a-z])/g, (_, letter: string) => letter.toUpperCase())}No`, columnName: `${moduleName.replace(/-/g, '_')}_no`, javaType: 'String', required: true, role: 'PERSISTENT', description: 'Business number' },
      { name: 'name', columnName: 'name', javaType: 'String', required: true, role: 'PERSISTENT', description: 'Business name' },
      { name: 'status', columnName: 'status', javaType: 'Integer', required: true, role: 'PERSISTENT', description: 'Business status' },
      { name: 'remark', columnName: 'remark', javaType: 'String', required: false, role: 'PERSISTENT', description: 'Remark' },
    ];
  const deliverables: PlanDraftV2['deliverables'] = [
    { kind: 'DDL', moduleSide: 'DOC', action: 'CREATE' },
    { kind: 'ENTITY', className: entityName, moduleSide: 'API', action: 'CREATE' },
    { kind: 'VO', className: `${entityName}VO`, moduleSide: 'API', action: 'CREATE' },
    { kind: 'MAPPER', className: `${entityName}Mapper`, moduleSide: 'IMPL', action: 'CREATE' },
    { kind: 'SERVICE', className: `I${entityName}Service`, moduleSide: 'IMPL', action: 'CREATE' },
    { kind: 'CONTROLLER', className: `${entityName}Controller`, moduleSide: 'IMPL', action: 'CREATE' },
  ];
  if (config.needFeign === true) deliverables.push({ kind: 'FEIGN', className: `I${entityName}Client`, moduleSide: 'API', action: 'CREATE' });
  if (config.needExcel === true) deliverables.push({ kind: 'EXCEL', className: `${entityName}Excel`, moduleSide: 'IMPL', action: 'CREATE' });

  const draft: PlanDraftV2 = {
    identity: { moduleName, entityName, tableName, basePackage: `org.springblade.${moduleName.replace(/-/g, '')}` },
    title: `${entityName} development plan`,
    requirementSummary: `${userInput || 'Implement the configured business module'}${entityModule ? ` using module ${entityModule.name}` : ''}. Mock mode still emits the same structured canonical contract used by the live workflow.`,
    fields,
    states: fields.some((field) => field.name === 'status')
      ? [{ name: 'businessState', values: ['DRAFT', 'ACTIVE'], transitions: [{ from: 'DRAFT', to: 'ACTIVE', trigger: 'activate' }], referenceField: 'status' }]
      : [],
    integrations: [{ type: 'API', sourceModule: moduleName, entrypoint: `${entityName}Controller.list` }],
    deliverables,
    architectureDecisions: [],
  };
  const contract = compileStructuredPlanDraft(draft);
  const canonicalMockPlan = renderStructuredPlan(draft, contract);
  const lines = canonicalMockPlan.split('\n');

  const send = (data: object) => {
    if (res.destroyed || res.writableEnded) return;
    res.write(`data: ${JSON.stringify(data)}\n\n`);
  };

  send({ type: 'progress', stage: 'analyzing', message: '正在分析需求 (Mock)...' });
  await sleep(100);
  send({ type: 'progress', stage: 'planning', message: '正在编译 Canonical Plan Contract v2 (Mock)...' });
  await sleep(100);

  for (const line of lines) {
    if (clientGone) return;
    send({ type: 'content', chunk: `${line}\n` });
  }

  send({ type: 'complete', tokensUsed: 0 });
  res.end();
}

function mockSplit(planContent: string) {
  const contract = compilePlanContract(planContent).contract;
  const orderedKinds = ['DDL', 'ENTITY', 'VO', 'MAPPER', 'SERVICE', 'CONTROLLER', 'FEIGN', 'EXCEL', 'CONFIG'];
  const groups = orderedKinds.flatMap((kind) => {
    const ids = contract.deliverables.filter((item) => item.kind === kind && item.action !== 'PROHIBIT').map((item) => item.id);
    return ids.length > 0 ? [{ title: `${kind} implementation`, ids }] : [];
  });
  const response = parseSplitModelResponse(JSON.stringify({
    subPlans: groups.map((item, index) => ({
      id: `sub_${index + 1}`,
      index: index + 1,
      title: item.title,
      planContent: `Implement canonical deliverables: ${item.ids.join(', ')}`,
      prerequisites: index === 0 ? [] : [`sub_${index}`],
      deliverableIds: item.ids,
    })),
  }), contract);
  if (!response.ok) throw new Error(response.error);
  return response.value;
}

function safeJsonParse(raw: string): Record<string, unknown> | null {
  if (!raw) return null;
  let body = raw.trim();
  // 去掉 ```json ... ``` 或 ``` ... ``` 包裹
  const fence = body.match(/^```(?:json)?\s*([\s\S]*?)```$/);
  if (fence) body = fence[1].trim();
  try {
    return JSON.parse(body);
  } catch {
    // 尝试从首个 { 到末尾 } 截取
    const start = body.indexOf('{');
    const end = body.lastIndexOf('}');
    if (start >= 0 && end > start) {
      try {
        return JSON.parse(body.slice(start, end + 1));
      } catch {
        return null;
      }
    }
    return null;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
