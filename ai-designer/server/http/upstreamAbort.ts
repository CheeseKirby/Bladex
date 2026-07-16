import type { EventEmitter } from 'node:events';

export type UpstreamAbortReason = 'timeout' | 'client-disconnect';

type CloseEventSource = Pick<EventEmitter, 'once' | 'removeListener'>;

/**
 * Couples an upstream AbortController to the downstream response lifetime.
 *
 * The upstream request is cancelled when either the total timeout expires or
 * the client connection closes, including disconnects before response headers
 * have been sent. Calling the returned cleanup function marks normal
 * completion and removes all timers/listeners.
 */
export function bindUpstreamAbort(
  responseEvents: CloseEventSource,
  controller: AbortController,
  timeoutMs: number,
  onAbort?: (reason: UpstreamAbortReason) => void,
): () => void {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error('timeoutMs must be a positive finite number');
  }

  let finished = false;

  const abort = (reason: UpstreamAbortReason) => {
    if (finished || controller.signal.aborted) return;
    controller.abort(new Error(reason));
    onAbort?.(reason);
  };

  const onClose = () => abort('client-disconnect');
  const timer = setTimeout(() => abort('timeout'), timeoutMs);
  timer.unref?.();
  responseEvents.once('close', onClose);

  return () => {
    if (finished) return;
    finished = true;
    clearTimeout(timer);
    responseEvents.removeListener('close', onClose);
  };
}
