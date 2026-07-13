/**
 * 运行时配置路由
 *
 * - GET  /api/config/llm  → 返回脱敏的当前 LLM 配置(无需鉴权)
 * - PUT  /api/config/llm  → 更新配置(需要 X-Admin-Token,或来源为本地回环)
 * - POST /api/config/llm/sync-to-partb → 同步到 Part B (转发 PUT 到 8111,带 X-Admin-Token)
 */

import { Router, Request, Response, NextFunction } from 'express';
import { getLlmConfig, getLlmConfigMasked, updateLlmConfig } from '../config/llmConfig';

export const configRouter = Router();

const ADMIN_TOKEN = (process.env.BFF_ADMIN_TOKEN || '').trim();
// 把 Part B 写入端点需要的 token 单独存,允许与 BFF_ADMIN_TOKEN 不同
const PART_B_ADMIN_TOKEN = (process.env.AI_WORKFLOW_ADMIN_TOKEN || '').trim();

/** 仅放行 (1) X-Admin-Token 与 BFF_ADMIN_TOKEN 匹配,或 (2) 未配置 token 且远端是回环 */
function requireAdmin(req: Request, res: Response, next: NextFunction): void {
  const token = (req.header('X-Admin-Token') || '').trim();
  if (ADMIN_TOKEN) {
    if (token === ADMIN_TOKEN) {
      next();
      return;
    }
    res.status(403).json({ success: false, msg: 'X-Admin-Token 不匹配' });
    return;
  }
  // 未配置 token: 只允许本地回环 — 防止任意页面通过浏览器修改凭据
  const remote = req.socket.remoteAddress || '';
  const isLoopback =
    remote === '127.0.0.1' ||
    remote === '::1' ||
    remote === '::ffff:127.0.0.1' ||
    remote.startsWith('127.');
  if (isLoopback) {
    next();
    return;
  }
  res.status(403).json({
    success: false,
    msg: `BFF_ADMIN_TOKEN 未配置, /api/config 写入端点拒绝非本地请求 (remoteAddr=${remote})`,
  });
}

configRouter.get('/llm', (_req: Request, res: Response) => {
  res.json({ success: true, data: getLlmConfigMasked() });
});

configRouter.put('/llm', requireAdmin, (req: Request, res: Response) => {
  const body = req.body as Record<string, unknown>;
  const patch: Record<string, unknown> = {};

  if (typeof body.baseUrl === 'string') {
    const newBase = body.baseUrl.trim();
    if (!isAllowedBaseUrl(newBase)) {
      res.status(400).json({
        success: false,
        msg: `baseUrl 不允许: 必须 http/https 且不能指向内网/元数据地址 (${newBase})`,
      });
      return;
    }
    patch.baseUrl = newBase;
  }
  if (typeof body.model === 'string') patch.model = body.model.trim();
  if (typeof body.anthropicVersion === 'string') patch.anthropicVersion = body.anthropicVersion.trim();
  if (typeof body.maxTokens === 'number' && body.maxTokens > 0) patch.maxTokens = body.maxTokens;

  // 鉴权字段: 含 '*' 视为脱敏值,不修改;空串视为清空;明文视为新值
  const isMasked = (v: unknown): v is string => typeof v === 'string' && v.includes('*');
  if (body.authToken === '') {
    patch.authToken = '';
  } else if (typeof body.authToken === 'string' && !isMasked(body.authToken)) {
    patch.authToken = body.authToken;
  }
  if (body.apiKey === '') {
    patch.apiKey = '';
  } else if (typeof body.apiKey === 'string' && !isMasked(body.apiKey)) {
    patch.apiKey = body.apiKey;
  }

  updateLlmConfig(patch);
  const masked = getLlmConfigMasked();
  // 不再打 baseUrl/model 进日志以减少配置变更追踪面;只记一个调用事件
  console.log(`[LLM] runtime updated  auth=${masked.hasAuthToken ? 'bearer' : masked.hasApiKey ? 'x-api-key' : 'NONE'}`);
  res.json({ success: true, data: masked });
});

/** 把当前配置同步到 Part B */
configRouter.post('/llm/sync-to-partb', requireAdmin, async (_req: Request, res: Response) => {
  const partBUrl = process.env.PART_B_URL || 'http://localhost:8111';
  const cfg = getLlmConfig();

  try {
    const headers: Record<string, string> = { 'content-type': 'application/json' };
    if (PART_B_ADMIN_TOKEN) headers['X-Admin-Token'] = PART_B_ADMIN_TOKEN;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10_000);
    let resp: globalThis.Response;
    try {
      resp = await fetch(`${partBUrl}/api/config/llm`, {
        method: 'PUT',
        headers,
        body: JSON.stringify({
          baseUrl: cfg.baseUrl,
          model: cfg.model,
          authToken: cfg.authToken,
          apiKey: cfg.apiKey,
          anthropicVersion: cfg.anthropicVersion,
          maxTokens: cfg.maxTokens,
        }),
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timer);
    }
    const text = await resp.text();
    if (!resp.ok) {
      res.status(502).json({ success: false, msg: `Part B ${resp.status}: ${text.slice(0, 300)}` });
      return;
    }
    res.json({ success: true, msg: 'Part B 配置已同步', partBResponse: tryParseJson(text) });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    res.status(502).json({ success: false, msg: `Part B 通信失败: ${msg}` });
  }
});

function tryParseJson(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return s;
  }
}

function isAllowedBaseUrl(url: string): boolean {
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return false;
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return false;
  const host = u.hostname.toLowerCase();
  if (!host) return false;
  if (host === 'localhost' || host === 'host.docker.internal') return true;
  if (host.startsWith('169.254.')) return false; // link-local / 云元数据
  if (host.startsWith('127.')) return false;
  if (host.startsWith('10.')) return false;
  if (host.startsWith('192.168.')) return false;
  if (host.startsWith('172.')) {
    const parts = host.split('.');
    const second = Number(parts[1]);
    if (second >= 16 && second <= 31) return false;
  }
  if (host === '::1' || host.startsWith('fe80:')) return false;
  return true;
}
