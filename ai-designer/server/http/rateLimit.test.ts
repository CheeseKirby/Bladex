import assert from 'node:assert/strict';
import test from 'node:test';

import { FixedWindowRateLimiter } from './rateLimit';

test('fixed-window limiter rejects requests over the configured maximum', () => {
  const limiter = new FixedWindowRateLimiter(2, 1_000);
  assert.equal(limiter.consume('client', 100).allowed, true);
  assert.equal(limiter.consume('client', 200).allowed, true);
  const rejected = limiter.consume('client', 300);
  assert.equal(rejected.allowed, false);
  assert.equal(rejected.remaining, 0);
});

test('fixed-window limiter resets after the window expires', () => {
  const limiter = new FixedWindowRateLimiter(1, 1_000);
  assert.equal(limiter.consume('client', 100).allowed, true);
  assert.equal(limiter.consume('client', 200).allowed, false);
  assert.equal(limiter.consume('client', 1_101).allowed, true);
});

test('fixed-window limiter rejects invalid configuration', () => {
  assert.throws(() => new FixedWindowRateLimiter(Number.NaN, 1_000), /finite positive/);
});
