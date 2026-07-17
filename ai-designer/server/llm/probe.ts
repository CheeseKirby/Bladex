import { buildAuthHeaders, getLlmConfig } from '../config/llmConfig';
import { fetchWithTransientRetry } from '../http/fetchRetry';

const DEFAULT_PROBE_TIMEOUT_MS = 20_000;
const PROBE_MAX_TOKENS = 512;

export async function probeLlmConnection(
  fetchImpl: typeof fetch = fetch,
  timeoutMs = DEFAULT_PROBE_TIMEOUT_MS,
): Promise<void> {
  const cfg = getLlmConfig();
  const base = cfg.baseUrl.replace(/\/+$/, '');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error('LLM connection test timed out')), timeoutMs);
  try {
    const response = await fetchWithTransientRetry(`${base}/v1/messages`, {
      method: 'POST',
      headers: buildAuthHeaders(),
      body: JSON.stringify({
        model: cfg.model,
        max_tokens: PROBE_MAX_TOKENS,
        messages: [{ role: 'user', content: 'Reply with OK only.' }],
      }),
      signal: controller.signal,
    }, fetchImpl);
    if (!response.ok) {
      const body = await response.text().catch(() => '');
      throw new Error(`LLM ${response.status}: ${body.slice(0, 300)}`);
    }
    const body = await response.json() as { content?: Array<{ type?: string; text?: string }> };
    const text = body.content?.find((block) => block.type === 'text')?.text;
    if (typeof text !== 'string' || !text.trim()) {
      throw new Error('LLM returned HTTP 200 but no Anthropic text content');
    }
  } finally {
    clearTimeout(timer);
  }
}
