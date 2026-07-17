import assert from 'node:assert/strict';
import test from 'node:test';
import { deriveGenerationIdentity, normalizeModuleName } from './generationIdentity';
import type { Project } from '../types/plan';

function project(planContent: string, config: Record<string, unknown> = {}): Project {
  return {
    id: 'p1', projectName: 'test', status: 'SUBPLANS_REVIEWED',
    modules: [{ id: 'm1', type: 'ENTITY', name: 'entity', icon: '', color: '', config }],
    masterPlan: { id: 'plan1', projectId: 'p1', version: 1, status: 'REVIEWED', planContent },
    subPlans: [],
  };
}

test('derives one canonical identity from configured entity module', () => {
  const identity = deriveGenerationIdentity(project('moduleName: ignored', {
    moduleName: 'safeprod', entityName: 'SpecialPeriod', tableName: 'blade_special_period',
  }));
  assert.deepEqual(identity, {
    moduleName: 'safeprod', entityName: 'SpecialPeriod', tableName: 'blade_special_period',
    basePackage: 'org.springblade.safeprod', apiModuleName: 'blade-safeprod-api',
    serviceModuleName: 'blade-safeprod', serviceName: 'blade-safeprod',
  });
});

test('reserved prose module cannot replace the business identity', () => {
  const identity = deriveGenerationIdentity(project(
    'moduleName: pom\nentityName: SpecialPeriod\ntableName: blade_special_period',
  ));
  assert.equal(identity.moduleName, 'specialperiod');
  assert.equal(identity.entityName, 'SpecialPeriod');
});

test('normalizes Blade module artifact names', () => {
  assert.equal(normalizeModuleName('blade-safeprod-api'), 'safeprod');
  assert.throws(() => normalizeModuleName('pom'));
});
