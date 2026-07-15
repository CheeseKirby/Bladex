import type { NextFunction, Request, RequestHandler, Response } from 'express';

export interface RateLimitDecision {
  allowed: boolean;
  remaining: number;
  retryAfterMs: number;
}

interface Bucket {
  windowStartedAt: number;
  count: number;
}

export class FixedWindowRateLimiter {
  private readonly buckets = new Map<string, Bucket>();

  constructor(
    private readonly maxRequests: number,
    private readonly windowMs: number,
    private readonly maxKeys = 10_000,
  ) {
    if (!Number.isFinite(maxRequests) || !Number.isFinite(windowMs) || maxRequests <= 0 || windowMs <= 0) {
      throw new Error('Rate-limit values must be finite positive numbers');
    }
  }

  consume(key: string, now = Date.now()): RateLimitDecision {
    let bucket = this.buckets.get(key);
    if (!bucket || now - bucket.windowStartedAt >= this.windowMs) {
      bucket = { windowStartedAt: now, count: 0 };
      this.ensureCapacity(now);
      this.buckets.set(key, bucket);
    }

    bucket.count += 1;
    const elapsed = now - bucket.windowStartedAt;
    const retryAfterMs = Math.max(0, this.windowMs - elapsed);
    return {
      allowed: bucket.count <= this.maxRequests,
      remaining: Math.max(0, this.maxRequests - bucket.count),
      retryAfterMs,
    };
  }

  private ensureCapacity(now: number): void {
    if (this.buckets.size < this.maxKeys) return;
    for (const [key, bucket] of this.buckets) {
      if (now - bucket.windowStartedAt >= this.windowMs) this.buckets.delete(key);
    }
    if (this.buckets.size >= this.maxKeys) {
      const oldestKey = this.buckets.keys().next().value as string | undefined;
      if (oldestKey) this.buckets.delete(oldestKey);
    }
  }
}

export function createRateLimitMiddleware(options: {
  maxRequests: number;
  windowMs: number;
  key?: (req: Request) => string;
}): RequestHandler {
  const limiter = new FixedWindowRateLimiter(options.maxRequests, options.windowMs);
  const keyOf = options.key ?? ((req: Request) => req.socket.remoteAddress || 'unknown');
  return (req: Request, res: Response, next: NextFunction): void => {
    const decision = limiter.consume(keyOf(req));
    res.setHeader('X-RateLimit-Remaining', String(decision.remaining));
    if (decision.allowed) {
      next();
      return;
    }
    res.setHeader('Retry-After', String(Math.max(1, Math.ceil(decision.retryAfterMs / 1_000))));
    res.status(429).json({ success: false, msg: 'Too many requests; retry later' });
  };
}
