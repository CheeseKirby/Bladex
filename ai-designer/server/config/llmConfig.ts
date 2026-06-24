/**
 * LLM 运行时配置
 *
 * 启动时从环境变量初始化:
 *   - ANTHROPIC_BASE_URL  (默认 https://api.anthropic.com)
 *   - ANTHROPIC_AUTH_TOKEN(优先) 或 ANTHROPIC_API_KEY
 *   - LLM_MODEL           (默认 glm-5.1)
 *
 * 运行时通过 PUT /api/config/llm 修改,改动立刻对后续 LLM 调用生效。
 * 进程重启回退到环境变量。
 */

export interface LlmRuntimeConfig {
  /** API Base URL, 默认 Anthropic 官方 */
  baseUrl: string;
  /** 模型名称 */
  model: string;
  /** Bearer token (ccswitch 风格), 优先于 apiKey */
  authToken: string;
  /** Anthropic 官方 API key, 作为 x-api-key 头 */
  apiKey: string;
  /** Anthropic API 版本 */
  anthropicVersion: string;
  /** 单次调用 max_tokens */
  maxTokens: number;
}

const ENV = {
  baseUrl: process.env.ANTHROPIC_BASE_URL || 'https://api.anthropic.com',
  model: process.env.LLM_MODEL || 'glm-5.1',
  authToken: process.env.ANTHROPIC_AUTH_TOKEN || '',
  apiKey: process.env.ANTHROPIC_API_KEY || '',
  anthropicVersion: process.env.ANTHROPIC_VERSION || '2023-06-01',
  maxTokens: Number(process.env.LLM_MAX_TOKENS || 8192),
};

let runtime: LlmRuntimeConfig = { ...ENV };

/** 获取当前生效配置 */
export function getLlmConfig(): LlmRuntimeConfig {
  return { ...runtime };
}

/** 更新配置(只覆盖传入字段) */
export function updateLlmConfig(patch: Partial<LlmRuntimeConfig>): LlmRuntimeConfig {
  runtime = {
    ...runtime,
    ...Object.fromEntries(Object.entries(patch).filter(([, v]) => v !== undefined)),
  };
  return getLlmConfig();
}

/** 是否已配置可用的鉴权信息 */
export function isLlmConfigured(): boolean {
  return Boolean(runtime.authToken || runtime.apiKey);
}

/** 输出对外可见的配置(token/key 全部 *,仅保留末 4 位用于辨识) */
export function getLlmConfigMasked(): LlmRuntimeConfig & { hasAuthToken: boolean; hasApiKey: boolean } {
  const mask = (s: string) => {
    if (!s) return '';
    if (s.length <= 8) return '*'.repeat(s.length);
    return '*'.repeat(s.length - 4) + s.slice(-4);
  };
  return {
    ...runtime,
    authToken: mask(runtime.authToken),
    apiKey: mask(runtime.apiKey),
    hasAuthToken: Boolean(runtime.authToken),
    hasApiKey: Boolean(runtime.apiKey),
  };
}

/** 构造调用 Anthropic 用的鉴权头(供 routes 复用) */
export function buildAuthHeaders(): Record<string, string> {
  const cfg = runtime;
  const headers: Record<string, string> = {
    'content-type': 'application/json',
    'anthropic-version': cfg.anthropicVersion,
  };
  if (cfg.authToken) {
    headers['authorization'] = `Bearer ${cfg.authToken}`;
  } else if (cfg.apiKey) {
    headers['x-api-key'] = cfg.apiKey;
  }
  return headers;
}

// 启动日志: 只暴露鉴权模式与 host(便于排查 baseUrl 是否生效),不打 model / 任何明文片段
let _host = '';
try { _host = new URL(runtime.baseUrl).host; } catch { _host = '(invalid baseUrl)'; }
console.log(
  `[LLM] init  host=${_host}  auth=${runtime.authToken ? 'bearer' : runtime.apiKey ? 'x-api-key' : 'NONE (mock)'}`
);
