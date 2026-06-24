import { useCallback, useRef } from 'react';
import { usePlanStore } from '../store/planStore';
import type { SSEMessage } from '../types/plan';

/**
 * LLM 流式响应 Hook
 *
 * 封装 SSE 流式响应处理逻辑。
 */
export function useLLMStream() {
  const setIsStreaming = usePlanStore((s) => s.setIsStreaming);
  const appendStreamingChunk = usePlanStore((s) => s.appendStreamingChunk);
  const setStreamingContent = usePlanStore((s) => s.setStreamingContent);
  const abortControllerRef = useRef<AbortController | null>(null);

  const startStream = useCallback(
    async (url: string, body: unknown, onComplete?: () => void) => {
      setStreamingContent('');
      setIsStreaming(true);

      const controller = new AbortController();
      abortControllerRef.current = controller;

      try {
        const response = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          signal: controller.signal,
        });

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }

        const reader = response.body?.getReader();
        if (!reader) throw new Error('No response body');

        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            if (line.startsWith('data: ')) {
              try {
                const msg: SSEMessage = JSON.parse(line.slice(6));
                if (msg.type === 'content' && msg.chunk) {
                  appendStreamingChunk(msg.chunk);
                }
                if (msg.type === 'complete') {
                  onComplete?.();
                }
              } catch {
                // skip malformed
              }
            }
          }
        }
      } catch (err: unknown) {
        if (err instanceof Error && err.name === 'AbortError') return;
        console.error('流式请求失败:', err);
      } finally {
        setIsStreaming(false);
      }
    },
    [setIsStreaming, appendStreamingChunk, setStreamingContent]
  );

  const abortStream = useCallback(() => {
    abortControllerRef.current?.abort();
  }, []);

  return { startStream, abortStream };
}
