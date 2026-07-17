const POSITIVE_TTL_MS = 60_000;
const NEGATIVE_TTL_MS = 10_000;
const FETCH_TIMEOUT_MS = 3_000;

let cached: { value: string | null; expiresAt: number } | null = null;
let inFlight: Promise<string | null> | null = null;

export function invalidateReferenceSummaryCache(): void {
  cached = null;
}

export async function getReferenceAdaptationSummary(
  partBUrl = process.env.PART_B_URL || 'http://localhost:8111',
  fetchImpl: typeof fetch = fetch,
): Promise<string | null> {
  const now = Date.now();
  if (cached && cached.expiresAt > now) return cached.value;
  if (inFlight) return inFlight;

  inFlight = (async () => {
    let value: string | null = null;
    try {
      const response = await fetchImpl(`${partBUrl.replace(/\/+$/, '')}/api/project/adaptation-summary`, {
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
      if (response.ok) {
        const body = await response.json() as { data?: unknown };
        value = typeof body.data === 'string' && body.data.trim() ? body.data : null;
      }
    } catch {
      value = null;
    }
    cached = {
      value,
      expiresAt: Date.now() + (value ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS),
    };
    return value;
  })().finally(() => {
    inFlight = null;
  });
  return inFlight;
}
