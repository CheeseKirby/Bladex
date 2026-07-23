import axios from 'axios';
import type { GeneratePlanRequest, PlanTransmitRequest, PlanTransmitResponse, GeneratedFileSummary, GeneratedFileDetail, ExecutionTimeline, TimelineStep, SubPlanTimeline } from '../types/api';
import type { SSEMessage, Project } from '../types/plan';

const api = axios.create({
  baseURL: '/api',
  timeout: 300_000, // Client default: 5 minutes; BFF default: 4 minutes.
});

const SSE_TIMEOUT_MS = 960_000; // Client budget: 16 minutes; BFF long-request budget: 15 minutes.

// === LLM 相关 API ===

/**
 * 流式需求分析 + 方案生成。
 *
 * 错误处理:
 * - HTTP 非 2xx → 触发 onError(Error("HTTP <code>: <body>"))
 * - 收到 type:'error' SSE 帧 → 触发 onError(Error(msg))
 * - 11 minutes without a chunk -> call onError and abort fetch
 * - 调用方可通过返回的 abort() 主动取消(组件卸载场景)
 */
export function generatePlanStream(
  request: GeneratePlanRequest,
  onMessage: (msg: SSEMessage) => void,
  onError: (err: Error) => void,
  onComplete: () => void
): { abort: () => void } {
  const controller = new AbortController();
  let watchdog: ReturnType<typeof setTimeout> | null = null;
  let settled = false;

  const cleanup = () => {
    if (watchdog) {
      clearTimeout(watchdog);
      watchdog = null;
    }
  };

  const fail = (error: Error) => {
    if (settled) return;
    settled = true;
    cleanup();
    onError(error);
  };

  const complete = () => {
    if (settled) return;
    settled = true;
    cleanup();
    onComplete();
  };

  const resetWatchdog = () => {
    if (watchdog) clearTimeout(watchdog);
    watchdog = setTimeout(() => {
      if (settled) return;
      settled = true;
      cleanup();
      controller.abort();
      onError(new Error('LLM stream timed out (660s without any chunk)'));
    }, SSE_TIMEOUT_MS);
  };

  resetWatchdog();

  (async () => {
    try {
      const response = await fetch('/api/llm/generate-plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errText = await response.text().catch(() => '');
        fail(new Error(`HTTP ${response.status}: ${errText.slice(0, 300)}`));
        return;
      }

      const reader = response.body?.getReader();
      if (!reader) {
        fail(new Error('No response body'));
        return;
      }

      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        resetWatchdog();
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (!line.startsWith('data: ')) continue;
          try {
            const msg: SSEMessage = JSON.parse(line.slice(6));
            // error 帧不进 onMessage,只走 onError,避免调用方在 onMessage 与 onError 重复处理 error 帧导致双重提示
            if (msg.type === 'error') {
              try {
                fail(new Error(msg.error || 'LLM stream generation failed'));
              } finally {
                void reader.cancel().catch(() => undefined);
              }
              return;
            }
            onMessage(msg);
            if (msg.type === 'complete') {
              try {
                complete();
              } finally {
                void reader.cancel().catch(() => undefined);
              }
              return;
            }
          } catch {
            // skip malformed JSON (心跳/keepalive)
          }
        }
      }
      fail(new Error('LLM stream ended before receiving a complete event'));
    } catch (err) {
      cleanup();
      // 主动取消(组件卸载/超时)不算错误
      if (err instanceof DOMException && err.name === 'AbortError') {
        // 已通过 watchdog 或调用方触发的 onError 处理过,避免重复
        return;
      }
      fail(err instanceof Error ? err : new Error(String(err)));
    }
  })();

  return {
    abort: () => {
      if (settled) return;
      settled = true;
      cleanup();
      controller.abort();
    },
  };
}

/** Structured split failure; deterministic blockers retain their rule evidence. */
export class SplitPlanError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly issues: Array<{ severity: 'ERROR' | 'WARN'; rule: string; message: string }> = [],
  ) {
    super(message);
    this.name = 'SplitPlanError';
  }
}

/** Split sub-plans without hiding deterministic or schema failures. */
export async function splitPlan(planContent: string, reviewId: string, projectId: string, subjectId: string, signal?: AbortSignal) {
  try {
    const res = await api.post('/llm/split-plan', { planContent, reviewId, projectId, subjectId }, { timeout: 960_000, signal });
    return res.data;
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.data && typeof error.response.data === 'object') {
      const payload = error.response.data as Record<string, unknown>;
      const issues = Array.isArray(payload.issues)
        ? payload.issues.filter((issue): issue is { severity: 'ERROR' | 'WARN'; rule: string; message: string } =>
          typeof issue === 'object' && issue !== null
          && ((issue as Record<string, unknown>).severity === 'ERROR' || (issue as Record<string, unknown>).severity === 'WARN')
          && typeof (issue as Record<string, unknown>).rule === 'string'
          && typeof (issue as Record<string, unknown>).message === 'string')
        : [];
      throw new SplitPlanError(
        typeof payload.error === 'string' ? payload.error : error.message,
        typeof payload.code === 'string' ? payload.code : 'SPLIT_INFRA_ERROR',
        issues,
      );
    }
    throw error;
  }
}

// === 阶段二: 双向补齐 API ===

/** 补齐需求 — LLM 根据短需求+模块配置反向生成完整业务需求描述 */
export async function enrichRequirement(userInput: string, modules: unknown[]) {
  const res = await api.post('/llm/enrich-requirement', { userInput, modules });
  return res.data;
}

/** 推荐模块 — LLM 根据需求文本反向建议拖入哪些模块 */
export async function suggestModules(userInput: string) {
  const res = await api.post('/llm/suggest-modules', { userInput });
  return res.data;
}

/**
 * 一键完备 — 固定执行: 先推荐模块 → 再基于"原需求 + 新模块"展开需求。
 *
 * <p>解决先后顺序问题: 用户无论起点是什么,只要点这个按钮,
 * 后台都会按确定的顺序串行调用两个端点,保证结果可重现。
 *
 * @param userInput 用户当前输入的需求(可能只有几个字)
 * @param existingModules 当前画布上已有的模块(避免重复推荐)
 * @returns suggestions 是新推荐的模块清单, enriched 是合并后展开的需求文本
 */
export async function completeOneShot(userInput: string, existingModules: unknown[]) {
  const res = await api.post('/llm/complete-one-shot', { userInput, existingModules }, { timeout: 540_000 });
  return res.data;
}

// === 方案管理 API ===

/** 保存项目 */
export async function saveProject(project: Project) {
  const res = await api.post('/plans/save', project);
  return res.data;
}

/** 加载项目 */
export async function loadProject(projectId: string) {
  const res = await api.get(`/plans/${projectId}`);
  return res.data;
}

/** 列出项目 */
export async function listProjects() {
  const res = await api.get('/plans');
  return res.data;
}

// === Part B 传输 API ===

/** 发送方案到 Part B */
export async function transmitPlan(request: PlanTransmitRequest): Promise<PlanTransmitResponse> {
  // 注意：实际部署时这里应指向 Part B 的地址
  const res = await api.post('/transmission/send', request);
  return res.data;
}

/** 查询 Part B 执行状态 */
export async function queryPartBStatus(receptionId: string) {
  const res = await api.get(`/transmission/status/${receptionId}`);
  return res.data;
}

/**
 * Part B 响应归一化。
 *
 * Part B 统一返回 { code, success, data, msg }(`success` 是布尔真相);
 * BFF 透传成功时原样转交该对象,通信失败时返回 { success: false, msg }(无 data)。
 *
 * 这里以 `success` 布尔字段为准(而非用 data 的存在性反推成功),并把 msg 带出,
 * 避免把“业务成功但 data 为空”误判成失败、也避免丢掉失败原因。
 * data 缺省时回退到调用方提供的 fallback。
 */
export interface PartBUnwrapped<T> {
  success: boolean;
  data: T;
  msg?: string;
}

export function unwrapPartBResponse<T>(
  raw: unknown,
  fallback: T,
  isValidData?: (data: unknown) => data is T,
): PartBUnwrapped<T> {
  if (!raw || typeof (raw as { success?: unknown }).success !== 'boolean') {
    return { success: false, data: fallback, msg: (raw as { msg?: string } | null)?.msg };
  }

  const response = raw as { success: boolean; data?: unknown; msg?: string | null };
  const msg = response.msg ?? undefined;
  if (!response.success) return { success: false, data: fallback, msg };
  if (response.data == null) return { success: true, data: fallback, msg };
  if (isValidData && !isValidData(response.data)) {
    return { success: false, data: fallback, msg: msg ?? 'Part B response data has an invalid shape' };
  }
  return { success: true, data: response.data as T, msg };
}

export async function unwrapPartBRequest<T>(
  request: PromiseLike<{ data: unknown }>,
  fallback: T,
  isValidData?: (data: unknown) => data is T,
): Promise<PartBUnwrapped<T>> {
  try {
    const response = await request;
    return unwrapPartBResponse(response.data, fallback, isValidData);
  } catch (error) {
    if (!axios.isAxiosError(error)) throw error;
    const normalized = unwrapPartBResponse<T>(error.response?.data, fallback, isValidData);
    return normalized.msg ? normalized : { ...normalized, msg: error.message };
  }
}

type UnknownRecord = Record<string, unknown>;

const isRecord = (data: unknown): data is UnknownRecord =>
  typeof data === 'object' && data !== null && !Array.isArray(data);
const isFiniteNumber = (data: unknown): data is number =>
  typeof data === 'number' && Number.isFinite(data);
const optionalString = (data: unknown): boolean => data == null || typeof data === 'string';
const optionalNumber = (data: unknown): boolean => data == null || isFiniteNumber(data);

export function isGeneratedFileSummary(data: unknown): data is GeneratedFileSummary {
  if (!isRecord(data)) return false;
  return isFiniteNumber(data.id)
    && isFiniteNumber(data.subPlanId)
    && typeof data.filePath === 'string'
    && typeof data.fileName === 'string'
    && optionalString(data.partASubPlanId)
    && optionalString(data.subPlanTitle)
    && optionalString(data.fileType)
    && optionalString(data.fileExtension)
    && optionalString(data.action)
    && optionalNumber(data.sizeBytes)
    && optionalNumber(data.lineCount)
    && optionalString(data.createTime);
}

export function isGeneratedFileSummaryArray(data: unknown): data is GeneratedFileSummary[] {
  return Array.isArray(data) && data.every(isGeneratedFileSummary);
}

export function isGeneratedFileDetail(data: unknown): data is GeneratedFileDetail {
  if (!isRecord(data)) return false;
  return isGeneratedFileSummary(data) && typeof data.content === 'string';
}

function isTimelineStep(data: unknown): data is TimelineStep {
  if (!isRecord(data)) return false;
  return isFiniteNumber(data.id)
    && typeof data.stage === 'string'
    && typeof data.status === 'string'
    && optionalString(data.action)
    && optionalString(data.filePath)
    && optionalString(data.reason)
    && optionalString(data.createTime);
}

function isSubPlanTimeline(data: unknown): data is SubPlanTimeline {
  if (!isRecord(data)) return false;
  return isFiniteNumber(data.subPlanId)
    && isFiniteNumber(data.fileCount)
    && Array.isArray(data.steps)
    && data.steps.every(isTimelineStep)
    && optionalString(data.partASubPlanId)
    && optionalNumber(data.index)
    && optionalString(data.title)
    && optionalString(data.status)
    && optionalString(data.errorMessage)
    && optionalString(data.startedAt)
    && optionalString(data.completedAt);
}

export function isExecutionTimeline(data: unknown): data is ExecutionTimeline {
  if (!isRecord(data)) return false;
  return typeof data.receptionId === 'string'
    && isFiniteNumber(data.totalSubPlans)
    && isFiniteNumber(data.completedSubPlans)
    && isFiniteNumber(data.failedSubPlans)
    && Array.isArray(data.subPlanTimelines)
    && data.subPlanTimelines.every(isSubPlanTimeline)
    && optionalString(data.overallStatus)
    && optionalString(data.moduleName)
    && optionalString(data.entityName)
    && optionalString(data.tableName)
    && optionalString(data.basePackage)
    && optionalString(data.frameworkVersion)
    && optionalString(data.javaVersion)
    && optionalString(data.outputDirectory)
    && optionalString(data.compileVerificationStatus)
    && optionalNumber(data.qualityErrorCount)
    && optionalNumber(data.qualityWarningCount);
}

/** 列出 Part B 生成的文件(摘要) */
export async function listPartBFiles(receptionId: string): Promise<PartBUnwrapped<GeneratedFileSummary[]>> {
  return unwrapPartBRequest(
    api.get(`/transmission/files/${receptionId}`),
    [] as GeneratedFileSummary[],
    isGeneratedFileSummaryArray,
  );
}

/** 查询单文件完整内容 */
export async function getPartBFileDetail(fileId: number): Promise<PartBUnwrapped<GeneratedFileDetail | null>> {
  return unwrapPartBRequest(
    api.get(`/transmission/file/${fileId}`),
    null as GeneratedFileDetail | null,
    isGeneratedFileDetail,
  );
}

/** 拉取执行进度时间线 */
export async function getPartBTimeline(receptionId: string): Promise<PartBUnwrapped<ExecutionTimeline | null>> {
  return unwrapPartBRequest(
    api.get(`/transmission/timeline/${receptionId}`),
    null as ExecutionTimeline | null,
    isExecutionTimeline,
  );
}
