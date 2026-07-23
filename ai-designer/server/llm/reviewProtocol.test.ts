import assert from 'node:assert/strict';
import { test } from 'node:test';

import { parseReviewModelResponse, parseReviewModelResponseWithRecovery } from './reviewProtocol';

const context = { round: 1, referenceSummaryAvailable: true, receivedAt: '2026-07-21T00:00:00.000Z' };

const emptyRepairs = { repairs: [], fixes: [], changeLog: [] };

test('valid review JSON is accepted with successful audit evidence', () => {
  const result = parseReviewModelResponse(JSON.stringify({
    passes: true,
    issues: [],
    ...emptyRepairs,
  }), context);

  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.value.passes, true);
  assert.deepEqual(result.value.repairs, []);
  assert.equal(result.evidence.parseStatus, 'SUCCESS');
  assert.equal(result.evidence.schemaValidationStatus, 'SUCCESS');
  assert.equal(result.evidence.referenceSummaryAvailable, true);
  assert.equal(result.evidence.rawResponseSha256.length, 64);
});

test('fenced JSON remains supported but is still schema validated', () => {
  const result = parseReviewModelResponse(`\`\`\`json
{"passes":false,"issues":[{"severity":"ERROR","rule":"X","message":"broken"}],"repairs":[],"fixes":[],"changeLog":[]}
\`\`\``, context);

  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.value.issues[0].severity, 'ERROR');
});

test('typed repair operations are strictly validated', () => {
  const result = parseReviewModelResponse(JSON.stringify({
    passes: false,
    issues: [{ severity: 'ERROR', rule: 'REF-DUPLICATE-CAPABILITY', message: 'binding missing' }],
    repairs: [{
      operation: 'BIND_EXISTING_SYMBOL',
      targetId: 'entity.risk-date',
      arguments: {
        referenceSymbol: 'org.springblade.safetycontrol.entity.SkRiskDates',
        decision: 'EXTEND',
        targetModule: 'safetycontrol',
      },
      resolves: ['REF-DUPLICATE-CAPABILITY'],
      preconditions: ['entity.risk-date exists'],
    }],
    fixes: [],
    changeLog: [],
  }), context);

  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.value.repairs[0].operation, 'BIND_EXISTING_SYMBOL');
});

test('empty or malformed response fails closed', () => {
  const empty = parseReviewModelResponse('', context);
  assert.equal(empty.ok, false);
  if (!empty.ok) {
    assert.equal(empty.evidence.parseStatus, 'FAILED');
    assert.match(empty.error, /empty/);
  }

  const malformed = parseReviewModelResponse('{"passes":true', context);
  assert.equal(malformed.ok, false);
  if (!malformed.ok) assert.equal(malformed.evidence.schemaValidationStatus, 'FAILED');
});

test('missing required arrays and invalid issue severity are infrastructure failures', () => {
  const missingIssues = parseReviewModelResponse('{"passes":true}', context);
  assert.equal(missingIssues.ok, false);
  if (!missingIssues.ok) {
    assert.equal(missingIssues.evidence.parseStatus, 'SUCCESS');
    assert.equal(missingIssues.evidence.schemaValidationStatus, 'FAILED');
    assert.match(missingIssues.error, /issues/);
  }

  const missingRepairs = parseReviewModelResponse(JSON.stringify({
    passes: true,
    issues: [],
    fixes: [],
    changeLog: [],
  }), context);
  assert.equal(missingRepairs.ok, false);
  if (!missingRepairs.ok) assert.match(missingRepairs.error, /repairs/);

  const missingFixes = parseReviewModelResponse(JSON.stringify({
    passes: true,
    issues: [],
    repairs: [],
    changeLog: [],
  }), context);
  assert.equal(missingFixes.ok, false);
  if (!missingFixes.ok) assert.match(missingFixes.error, /fixes/);

  const invalidSeverity = parseReviewModelResponse(JSON.stringify({
    passes: false,
    issues: [{ severity: 'INFO', rule: 'X', message: 'not allowed' }],
    ...emptyRepairs,
  }), context);
  assert.equal(invalidSeverity.ok, false);
  if (!invalidSeverity.ok) assert.match(invalidSeverity.error, /severity/);
});

test('invalid repair, section fix and change-log shapes are rejected', () => {
  const badRepair = parseReviewModelResponse(JSON.stringify({
    passes: false,
    issues: [{ severity: 'ERROR', rule: 'X', message: 'broken' }],
    repairs: [{ operation: 'UNKNOWN', arguments: {}, resolves: [], preconditions: [] }],
    fixes: [],
    changeLog: [],
  }), context);
  assert.equal(badRepair.ok, false);

  const badFix = parseReviewModelResponse(JSON.stringify({
    passes: false,
    issues: [{ severity: 'ERROR', rule: 'X', message: 'broken' }],
    repairs: [],
    fixes: [{ section: '', newContent: 'replacement' }],
    changeLog: [],
  }), context);
  assert.equal(badFix.ok, false);

  const badLog = parseReviewModelResponse(JSON.stringify({
    passes: true,
    issues: [],
    repairs: [],
    fixes: [],
    changeLog: [{ what: 'x' }],
  }), context);
  assert.equal(badLog.ok, false);
});

test('one audited schema recovery can repair malformed JSON but still uses strict validation', async () => {
  let calls = 0;
  const result = await parseReviewModelResponseWithRecovery('{"passes":false', context, async (_raw, error) => {
    calls += 1;
    assert.match(error, /parse failed|does not contain/);
    return JSON.stringify({
      passes: false,
      issues: [{ severity: 'ERROR', rule: 'X', message: 'still blocked' }],
      repairs: [{
        operation: 'DECLARE_ARCHITECTURE_DECISION',
        arguments: { decision: 'decide', rationale: 'required', evidence: ['ref-x'] },
        resolves: ['X'],
        preconditions: ['ref-x exists'],
      }],
      fixes: [],
      changeLog: [],
    });
  });

  assert.equal(calls, 1);
  assert.equal(result.ok, true);
  assert.equal(result.recovered, true);
  assert.equal(result.evidence.length, 2);
  assert.equal(result.evidence[0].responseKind, 'PRIMARY');
  assert.equal(result.evidence[1].responseKind, 'SCHEMA_RECOVERY');
});

test('invalid schema recovery remains fail-closed', async () => {
  const result = await parseReviewModelResponseWithRecovery('bad', context, async () => '{"passes":true}');
  assert.equal(result.ok, false);
  assert.equal(result.evidence.length, 2);
  if (!result.ok) assert.match(result.error, /schema recovery invalid/);
});