export async function fetchWithTransientRetry(
  input: string | URL,
  init: RequestInit,
  fetchImpl: typeof fetch = fetch,
  retries = 2,
): Promise<Response> {
  let lastError: unknown;
  for (let attempt = 0; attempt <= retries; attempt += 1) {
    if (init.signal?.aborted) throw init.signal.reason ?? new Error('Request aborted');
    try {
      return await fetchImpl(input, init);
    } catch (error) {
      lastError = error;
      const transient = error instanceof TypeError || (error instanceof Error && /fetch failed|ECONNRESET|ETIMEDOUT|socket/i.test(error.message));
      if (!transient || attempt >= retries || init.signal?.aborted) throw error;
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(resolve, attempt === 0 ? 300 : 1000);
        init.signal?.addEventListener('abort', () => { clearTimeout(timer); reject(init.signal?.reason); }, { once: true });
      });
    }
  }
  throw lastError;
}
