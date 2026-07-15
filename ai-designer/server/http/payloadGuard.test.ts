import assert from 'node:assert/strict';
import test from 'node:test';

import { validatePayload } from './payloadGuard';

const limits = {
  maxDepth: 2,
  maxArrayItems: 2,
  maxObjectKeys: 4,
  maxStringLength: 5,
  maxTotalStringLength: 8,
};

test('payload guard accepts a bounded request', () => {
  assert.equal(validatePayload({ a: '123', b: ['12'] }, limits), null);
});

test('payload guard rejects oversized strings and arrays', () => {
  assert.match(validatePayload({ a: '123456' }, limits) || '', /string field/);
  assert.match(validatePayload({ a: [1, 2, 3] }, limits) || '', /Array size/);
});

test('payload guard rejects excessive nesting and aggregate text', () => {
  assert.match(validatePayload({ a: { b: { c: 1 } } }, limits) || '', /nesting/);
  assert.match(validatePayload({ a: '12345', b: '1234' }, limits) || '', /Total string/);
});
