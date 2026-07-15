import assert from 'node:assert/strict';
import test from 'node:test';

import { isAdminRequestAllowed, isLoopbackAddress } from './adminGuard';

test('loopback address detection covers IPv4 and IPv6 forms', () => {
  assert.equal(isLoopbackAddress('127.0.0.1'), true);
  assert.equal(isLoopbackAddress('127.10.20.30'), true);
  assert.equal(isLoopbackAddress('::1'), true);
  assert.equal(isLoopbackAddress('::ffff:127.0.0.1'), true);
  assert.equal(isLoopbackAddress('192.168.1.8'), false);
});

test('an unconfigured BFF token allows loopback only', () => {
  assert.equal(isAdminRequestAllowed('127.0.0.1', undefined, ''), true);
  assert.equal(isAdminRequestAllowed('192.168.1.8', undefined, ''), false);
});

test('a configured BFF token is required even for loopback requests', () => {
  assert.equal(isAdminRequestAllowed('127.0.0.1', undefined, 'secret'), false);
  assert.equal(isAdminRequestAllowed('192.168.1.8', 'secret', 'secret'), true);
  assert.equal(isAdminRequestAllowed('192.168.1.8', 'wrong', 'secret'), false);
});
