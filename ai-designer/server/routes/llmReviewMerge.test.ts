import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mergeReviewIssues } from './llm';

test('semantic ERROR is not hidden by a deterministic issue sharing only the same rule', () => {
  const result = mergeReviewIssues(
    [{ severity: 'WARN', rule: 'SHARED-RULE', message: 'deterministic warning' }],
    [{ severity: 'ERROR', rule: 'SHARED-RULE', message: 'different semantic blocker' }],
  );
  assert.equal(result.length, 2);
  assert.ok(result.some((issue) => issue.severity === 'ERROR' && issue.message === 'different semantic blocker'));
});

test('exact duplicate issue is severity-upgraded rather than duplicated', () => {
  const result = mergeReviewIssues(
    [{ severity: 'WARN', rule: 'SAME', message: 'same issue' }],
    [{ severity: 'ERROR', rule: 'SAME', message: 'same issue' }],
  );
  assert.deepEqual(result, [{ severity: 'ERROR', rule: 'SAME', message: 'same issue' }]);
});
