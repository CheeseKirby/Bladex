import assert from 'node:assert/strict';
import { test } from 'node:test';
import { assertSingleConfiguredEntity, normalizeOneShotSuggestions } from './oneShotNormalization';

test('one-shot normalization keeps one entity and preserves non-entity capabilities', () => {
  const normalized = normalizeOneShotSuggestions([
    { type: 'ENTITY', name: 'special period', config: { entityName: 'SpecialPeriod' } },
    { type: 'ENTITY', name: 'upgrade rule', config: { entityName: 'FireUpgradeRule' } },
    { type: 'CONFIG', name: 'upgrade config', config: { configName: 'UpgradeConfig' } },
  ]);
  assert.deepEqual(normalized.map((item) => item.type), ['ENTITY', 'CONFIG']);
  assert.equal(normalized[0].config?.entityName, 'SpecialPeriod');
});

test('generation preflight rejects contradictory entity identities before calling the model', () => {
  assert.throws(() => assertSingleConfiguredEntity([
    { type: 'ENTITY', name: 'SpecialPeriod' }, { type: 'ENTITY', name: 'FireUpgradeRule' },
  ]), /PLAN_INPUT_CONFLICT/);
  assert.doesNotThrow(() => assertSingleConfiguredEntity([{ type: 'ENTITY', name: 'SpecialPeriod' }]));
});
