import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createSuggestedModules, needsAutomaticInputPreparation } from './planInputPreparation';
import type { DraggedModule } from '../types/plan';

const entity = (config: DraggedModule['config']): DraggedModule => ({
  id: 'entity-1', type: 'ENTITY', name: 'Special period', icon: '', color: '#000', config,
});

test('raw requirement without a canonical entity is prepared automatically', () => {
  assert.equal(needsAutomaticInputPreparation('\u65b0\u589e\u7279\u6b8a\u65f6\u6bb5\u52a8\u706b\u7ba1\u7406', []), true);
});

test('one-click field inventory with optional markers is ready for deterministic generation', () => {
  const requirement = [
    'periodName / String / \u5fc5\u586b / name',
    'periodType / Integer / \u5fc5\u586b / type',
    'startTime / String / \u9009\u586b / start',
  ].join('\n');
  assert.equal(needsAutomaticInputPreparation(requirement, [entity({
    moduleName: 'specialperiod', entityName: 'SpecialPeriod', tableName: 'blade_special_period',
  })]), false);
});

test('configured entity fields are sufficient even for concise prose', () => {
  assert.equal(needsAutomaticInputPreparation('concise requirement', [entity({
    moduleName: 'specialperiod', entityName: 'SpecialPeriod', tableName: 'blade_special_period',
    fields: [
      { name: 'periodName', type: 'String', comment: 'name', nullable: false },
      { name: 'periodType', type: 'Integer', comment: 'type', nullable: false },
      { name: 'startTime', type: 'String', comment: 'start', nullable: true },
    ],
  })]), false);
});

test('suggestions are validated and materialized as canvas modules', () => {
  const modules = createSuggestedModules([
    { type: 'ENTITY', name: 'Special period', icon: '\ud83d\udce6', config: { entityName: 'SpecialPeriod' } },
    { type: 'UNKNOWN', name: 'invalid', config: {} },
  ]);
  assert.equal(modules.length, 1);
  assert.equal(modules[0].type, 'ENTITY');
  assert.equal(modules[0].config.entityName, 'SpecialPeriod');
});
