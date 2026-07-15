import type { NextFunction, Request, RequestHandler, Response } from 'express';

export interface PayloadLimits {
  maxDepth: number;
  maxArrayItems: number;
  maxObjectKeys: number;
  maxStringLength: number;
  maxTotalStringLength: number;
}

export const DEFAULT_LLM_PAYLOAD_LIMITS: PayloadLimits = {
  maxDepth: 12,
  maxArrayItems: 200,
  maxObjectKeys: 5_000,
  maxStringLength: 500_000,
  maxTotalStringLength: 1_000_000,
};

export function validatePayload(value: unknown, limits = DEFAULT_LLM_PAYLOAD_LIMITS): string | null {
  const state = { totalStringLength: 0, objectKeys: 0 };

  function visit(current: unknown, depth: number): string | null {
    if (depth > limits.maxDepth) return `JSON nesting exceeds ${limits.maxDepth}`;
    if (typeof current === 'string') {
      if (current.length > limits.maxStringLength) {
        return `A string field exceeds ${limits.maxStringLength} characters`;
      }
      state.totalStringLength += current.length;
      if (state.totalStringLength > limits.maxTotalStringLength) {
        return `Total string content exceeds ${limits.maxTotalStringLength} characters`;
      }
      return null;
    }
    if (current === null || typeof current !== 'object') return null;
    if (Array.isArray(current)) {
      if (current.length > limits.maxArrayItems) return `Array size exceeds ${limits.maxArrayItems}`;
      for (const item of current) {
        const error = visit(item, depth + 1);
        if (error) return error;
      }
      return null;
    }

    const entries = Object.entries(current as Record<string, unknown>);
    state.objectKeys += entries.length;
    if (state.objectKeys > limits.maxObjectKeys) return `Object key count exceeds ${limits.maxObjectKeys}`;
    for (const [, child] of entries) {
      const error = visit(child, depth + 1);
      if (error) return error;
    }
    return null;
  }

  return visit(value, 0);
}

export function createPayloadGuard(limits = DEFAULT_LLM_PAYLOAD_LIMITS): RequestHandler {
  return (req: Request, res: Response, next: NextFunction): void => {
    const error = validatePayload(req.body, limits);
    if (error) {
      res.status(413).json({ success: false, msg: error });
      return;
    }
    next();
  };
}
