import axios from 'axios';
import type { GeneratePlanRequest, PlanTransmitRequest, PlanTransmitResponse, GeneratedFileSummary, GeneratedFileDetail, ExecutionTimeline } from '../types/api';
import type { SSEMessage, Project } from '../types/plan';

const api = axios.create({
  baseURL: '/api',
  timeout: 240000, // 全局 4 分钟(大项目生成/拆分等较慢)
});

const SSE_TIMEOUT_MS = 360_000; // 6 分钟无任何 chunk 视为挂死(大方案流式生成较慢)

// === LLM 相关 API ===

/**
 * 流式需求分析 + 方案生成。
 *
 * 错误处理:
 * - HTTP 非 2xx → 触发 onError(Error("HTTP <code>: <body>"))
 * - 收到 type:'error' SSE 帧 → 触发 onError(Error(msg))
 * - 3 分钟未收到任何 chunk → 触发 onError 并 abort fetch
 * - 调用方可通过返回的 abort() 主动取消(组件卸载场景)
 */
export async function generatePlanStream(
  request: GeneratePlanRequest,
  onMessage: (msg: SSEMessage) => void,
  onError: (err: Error) => void,
  onComplete: () => void
): Promise<{ abort: () => void }> {
  const controller = new AbortController();
  let watchdog: ReturnType<typeof setTimeout> | null = null;
  const resetWatchdog = () => {
    if (watchdog) clearTimeout(watchdog);
    watchdog = setTimeout(() => {
      controller.abort();
      onError(new Error('LLM 流式响应超时 (180s 无任何 chunk)'));
    }, SSE_TIMEOUT_MS);
  };

  const cleanup = () => {
    if (watchdog) {
      clearTimeout(watchdog);
      watchdog = null;
    }
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
        // BFF 在尚未开始 SSE 时返回 502 JSON,直接读出错误
        const errText = await response.text().catch(() => '');
        cleanup();
        onError(new Error(`HTTP ${response.status}: ${errText.slice(0, 300)}`));
        return;
      }

      const reader = response.body?.getReader();
      if (!reader) {
        cleanup();
        onError(new Error('No response body'));
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
            onMessage(msg);
            if (msg.type === 'error') {
              cleanup();
              onError(new Error(msg.error || 'LLM 流式生成失败'));
              try { await reader.cancel(); } catch { /* noop */ }
              return;
            }
            if (msg.type === 'complete') {
              cleanup();
              onComplete();
              return;
            }
          } catch {
            // skip malformed JSON (心跳/keepalive)
          }
        }
      }
      cleanup();
      onComplete();
    } catch (err) {
      cleanup();
      // 主动取消(组件卸载/超时)不算错误
      if (err instanceof DOMException && err.name === 'AbortError') {
        // 已通过 watchdog 或调用方触发的 onError 处理过,避免重复
        return;
      }
      onError(err instanceof Error ? err : new Error(String(err)));
    }
  })();

  return { abort: () => { cleanup(); controller.abort(); } };
}

/** 审查方案（非流式） - 审查-修复闭环(最多2轮)LLM 较慢,设 10 分钟超时 */
export async function reviewPlan(planContent: string, stage: 'master' | 'subplan') {
  const res = await api.post('/llm/review-plan', { planContent, stage }, { timeout: 600000 });
  return res.data;
}

/** 拆分子方案 */
export async function splitPlan(planContent: string) {
  const res = await api.post('/llm/split-plan', { planContent });
  return res.data;
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
  const res = await api.post('/llm/complete-one-shot', { userInput, existingModules });
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

/** 列出 Part B 生成的文件(摘要) */
export async function listPartBFiles(receptionId: string): Promise<{ success: boolean; data: GeneratedFileSummary[] }> {
  const res = await api.get(`/transmission/files/${receptionId}`);
  // Part B 包装格式: { code, success, data, msg } — 转成 { success, data }
  const raw = res.data;
  if (raw?.data && Array.isArray(raw.data)) {
    return { success: true, data: raw.data };
  }
  return { success: false, data: [] };
}

/** 查询单文件完整内容 */
export async function getPartBFileDetail(fileId: number): Promise<{ success: boolean; data: GeneratedFileDetail | null }> {
  const res = await api.get(`/transmission/file/${fileId}`);
  const raw = res.data;
  if (raw?.data) {
    return { success: true, data: raw.data };
  }
  return { success: false, data: null };
}

/** 拉取执行进度时间线 */
export async function getPartBTimeline(receptionId: string): Promise<{ success: boolean; data: ExecutionTimeline | null }> {
  const res = await api.get(`/transmission/timeline/${receptionId}`);
  const raw = res.data;
  if (raw?.data) {
    return { success: true, data: raw.data };
  }
  return { success: false, data: null };
}
