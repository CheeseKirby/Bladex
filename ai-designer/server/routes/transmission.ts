/**
 * Part B 传输代理路由
 *
 * 当 PART_B_URL 未设置且 BFF_MOCK_PART_B=true 时使用 Mock 响应,便于离开 Part B 单独开发 Part A。
 * 否则透传到真实 Part B(默认 http://localhost:8110)。
 *
 * 所有透传调用都带 10s 超时,避免上游慢响应拖死 BFF。
 */

import { Router, Request, Response } from 'express';

export const transmissionRouter = Router();

const PART_B_URL = process.env.PART_B_URL || 'http://localhost:8110';
const USE_MOCK = process.env.BFF_MOCK_PART_B === 'true';
const PROXY_TIMEOUT_MS = 10_000;
// 远程文件/时间线拉取允许更长一些(包含 LLM 响应时间)
const LONG_PROXY_TIMEOUT_MS = 30_000;

interface FetchOpts {
  method?: string;
  body?: unknown;
  timeoutMs?: number;
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
    const init: RequestInit = {
      method: opts.method ?? 'GET',
      headers: { 'Content-Type': 'application/json' },
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
transmissionRouter.post('/send', async (req: Request, res: Response) => {
  const planData = req.body;

  // Mock 模式: 显式通过 BFF_MOCK_PART_B=true 启用 — 避免生产环境因 PART_B_URL 漏配静默走 Mock
  if (USE_MOCK) {
    const receptionId = `rec_${Date.now()}`;
    const subPlanStatuses: Record<string, string> = {};
    (planData.subPlans || []).forEach((sp: { id: string }) => {
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
