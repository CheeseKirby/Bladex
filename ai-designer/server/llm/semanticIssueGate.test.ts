import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import type { PlanContract } from './planContract';
import { gateSemanticIssues, normalizeRule } from './semanticIssueGate';
import type { ReferenceReviewEvidence } from '../services/referenceSummary';

function fixture(): PlanContract {
  return JSON.parse(readFileSync('../contracts/fixtures/canonical-plan-contract-v2.json', 'utf8')) as PlanContract;
}

function noReference(): ReferenceReviewEvidence {
  return { adaptationSummary: null, search: null, searchStatus: 'SUCCESS', searchDurationMs: 0 };
}

test('closed field ownership downgrades an unsupported semantic mismatch claim', () => {
  const contract = fixture();
  const result = gateSemanticIssues([{
    severity: 'ERROR', rule: 'FIELD_ENTITY_MISMATCH', message: 'field does not belong to entity',
    elementIds: ['field.ticket.ticket-no'],
  }], [], contract, noReference());
  assert.equal(result.acceptedErrorCount, 0);
  assert.equal(result.downgradedErrorCount, 1);
  assert.deepEqual(result.issues.map((issue) => [issue.severity, issue.rule]), [['WARN', 'SEMANTIC-CLAIM-UNVERIFIED']]);
});

test('an existing deterministic blocker keeps the matching semantic rule blocking', () => {
  const contract = fixture();
  const result = gateSemanticIssues([{
    severity: 'ERROR', rule: 'field_entity_mismatch', message: 'semantic detail',
  }], [{ severity: 'ERROR', rule: 'FIELD-ENTITY-MISMATCH', message: 'deterministic evidence' }], contract, noReference());
  assert.equal(result.acceptedErrorCount, 1);
  assert.equal(result.issues[0].severity, 'ERROR');
  assert.equal(result.issues[0].rule, 'FIELD-ENTITY-MISMATCH');
});

test('unknown contract element ids cannot create a semantic blocker', () => {
  const result = gateSemanticIssues([{
    severity: 'ERROR', rule: 'STATE_OWNER_MISMATCH', message: 'unknown state', elementIds: ['state.missing'],
  }], [], fixture(), noReference());
  assert.equal(result.issues[0].severity, 'WARN');
  assert.equal(result.issues[0].rule, 'SEMANTIC-CLAIM-UNVERIFIED');
});

test('a missing integration entrypoint is independently verifiable', () => {
  const contract = fixture();
  contract.integrations[0] = { ...contract.integrations[0], entrypoint: undefined };
  const result = gateSemanticIssues([{
    severity: 'ERROR', rule: 'INTEGRATION_ENTRY_MISSING', message: 'entrypoint is missing',
    elementIds: [contract.integrations[0].id], evidence: { source: 'CONTRACT_INVARIANT' },
  }], [], contract, noReference());
  assert.equal(result.acceptedErrorCount, 1);
  assert.equal(result.issues[0].severity, 'ERROR');
});

test('a reference architecture decision can keep a manual decision blocker', () => {
  const reference: ReferenceReviewEvidence = {
    adaptationSummary: 'profile',
    searchStatus: 'SUCCESS',
    searchDurationMs: 1,
    search: {
      snapshotId: 'ref-1234567890', intent: 'ticket', symbols: [], relations: [], anomalies: [],
      decisions: [{ capability: 'ticket', decision: 'ARCHITECTURE_DECISION_REQUIRED', confidence: 0.9, reason: 'conflict', evidenceSymbols: [] }],
    },
  };
  const result = gateSemanticIssues([{
    severity: 'ERROR', rule: 'ARCHITECTURE_DECISION_REQUIRED', message: 'manual decision required',
    evidence: { source: 'REFERENCE_DECISION' },
  }], [], fixture(), reference);
  assert.equal(result.acceptedErrorCount, 1);
  assert.equal(result.issues[0].severity, 'ERROR');
});

test('rule normalization is stable across underscores and punctuation', () => {
  assert.equal(normalizeRule(' field__entity mismatch '), 'FIELD-ENTITY-MISMATCH');
});
