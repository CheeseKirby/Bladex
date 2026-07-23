import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { PlanContract } from './planContract';
import { parseSplitModelResponse, parseSplitModelResponseWithRecovery } from './splitProtocol';

const contract: PlanContract = {
  contractVersion: '2.0',
  sourceHash: 'x',
  sourceMode: 'STRUCTURED',
  rulesetVersion: 'canonical-plan-v2',
  identity: { moduleName: 'demo', entityName: 'Demo', tableName: 'blade_demo', basePackage: 'org.springblade.demo', apiModuleName: 'blade-demo-api', serviceModuleName: 'blade-demo', serviceName: 'blade-demo' },
  fields: [],
  domains: [{ id: 'domain.demo', name: 'demo', ownerModuleIds: ['module.demo'] }],
  modules: [{ id: 'module.demo', name: 'demo', kind: 'NEW' }],
  aggregates: [{ id: 'aggregate.demo', name: 'DemoAggregate', domainId: 'domain.demo', rootEntityId: 'entity.demo' }],
  entities: [{ id: 'entity.demo', name: 'Demo', moduleId: 'module.demo', aggregateId: 'aggregate.demo', table: 'blade_demo', fields: [] }],
  states: [],
  integrations: [],
  deliverables: [
    { id: 'd.ddl', kind: 'DDL', name: 'DDL' },
    { id: 'd.entity', kind: 'ENTITY', name: 'Entity' },
    { id: 'd.mapper', kind: 'MAPPER', name: 'Mapper' },
    { id: 'd.service', kind: 'SERVICE', name: 'Service' },
    { id: 'd.controller', kind: 'CONTROLLER', name: 'Controller' },
  ],
  referenceBindings: [],
  architectureDecisions: [],
};

test('valid split is contract-covered, dependency-closed and receives the canonical contract', () => {
  const result = parseSplitModelResponse(JSON.stringify({
    subPlans: [
      { id: 'sub_1', index: 1, title: 'Database DDL', planContent: 'CREATE TABLE blade_demo', prerequisites: [], deliverableIds: ['d.ddl'] },
      { id: 'sub_2', index: 2, title: 'Entity', planContent: 'Demo Entity', prerequisites: ['sub_1'], deliverableIds: ['d.entity'] },
      { id: 'sub_3', index: 3, title: 'Mapper and Service', planContent: 'DemoMapper DemoService', prerequisites: ['sub_2'], deliverableIds: ['d.mapper', 'd.service'] },
      { id: 'sub_4', index: 4, title: 'Controller API', planContent: 'DemoController REST', prerequisites: ['sub_3'], deliverableIds: ['d.controller'] },
    ],
  }), contract);

  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.value.subPlans.length, 4);
  assert.match(result.value.subPlans[0].planContent, /```plan-contract/);
  assert.equal(result.value.subPlans[3].prerequisites[0], 'sub_3');
});

test('split schema recovery is attempted once and remains contract validated', async () => {
  let recoveryCalls = 0;
  const recovered = await parseSplitModelResponseWithRecovery(
    JSON.stringify({ subPlans: [{ id: 'sub_1', index: 1, title: 'bad', planContent: 'bad', prerequisites: 0, deliverableIds: ['d.ddl'] }] }),
    contract,
    async () => {
      recoveryCalls += 1;
      return JSON.stringify({ subPlans: [
        { id: 'sub_1', index: 1, title: 'DDL and entity', planContent: 'DDL entity', prerequisites: [], deliverableIds: ['d.ddl', 'd.entity'] },
        { id: 'sub_2', index: 2, title: 'Mapper and service', planContent: 'Mapper service', prerequisites: ['sub_1'], deliverableIds: ['d.mapper', 'd.service'] },
        { id: 'sub_3', index: 3, title: 'Controller', planContent: 'Controller', prerequisites: ['sub_2'], deliverableIds: ['d.controller'] },
      ] });
    },
  );
  assert.equal(recoveryCalls, 1);
  assert.equal(recovered.schemaRecovered, true);
  assert.equal(recovered.parsed.ok, true);

  const stillInvalid = await parseSplitModelResponseWithRecovery('not-json', contract, async () => '{"subPlans":[]}');
  assert.equal(stillInvalid.schemaRecovered, true);
  assert.equal(stillInvalid.parsed.ok, false);
});

test('malformed, uncovered and cyclic split responses fail closed', () => {
  assert.equal(parseSplitModelResponse('not json', contract).ok, false);

  const uncovered = parseSplitModelResponse(JSON.stringify({
    subPlans: [{ id: 'sub_1', index: 1, title: 'Entity', planContent: 'Entity only', prerequisites: [], deliverableIds: ['d.entity'] }],
  }), contract);
  assert.equal(uncovered.ok, false);
  if (!uncovered.ok) assert.match(uncovered.error, /does not cover/);

  const cyclic = parseSplitModelResponse(JSON.stringify({
    subPlans: [
      { id: 'sub_1', index: 1, title: 'Database DDL Entity Mapper Service', planContent: 'DDL Entity Mapper Service', prerequisites: ['sub_2'], deliverableIds: ['d.ddl', 'd.entity', 'd.mapper', 'd.service'] },
      { id: 'sub_2', index: 2, title: 'Controller API', planContent: 'Controller', prerequisites: ['sub_1'], deliverableIds: ['d.controller'] },
    ],
  }), contract);
  assert.equal(cyclic.ok, false);
  if (!cyclic.ok) assert.match(cyclic.error, /cycle/);
});

test('unknown prerequisites and non-contiguous indexes fail closed', () => {
  const unknown = parseSplitModelResponse(JSON.stringify({
    subPlans: [
      { id: 'sub_1', index: 2, title: 'Database DDL Entity Mapper Service Controller', planContent: 'all layers', prerequisites: ['missing'], deliverableIds: ['d.ddl', 'd.entity', 'd.mapper', 'd.service', 'd.controller'] },
    ],
  }), contract);
  assert.equal(unknown.ok, false);
  if (!unknown.ok) assert.match(unknown.error, /contiguous|unknown prerequisite/);
});


test('duplicate and prohibited deliverable assignments fail closed', () => {
  const duplicate = parseSplitModelResponse(JSON.stringify({ subPlans: [{
    id: 'sub_1', index: 1, title: 'All', planContent: 'all', prerequisites: [],
    deliverableIds: ['d.ddl', 'd.ddl', 'd.entity', 'd.mapper', 'd.service', 'd.controller'],
  }] }), contract);
  assert.equal(duplicate.ok, false);
  if (!duplicate.ok) assert.match(duplicate.error, /duplicates/);

  const prohibitedContract: PlanContract = {
    ...contract,
    deliverables: [...contract.deliverables, { id: 'd.no-excel', kind: 'EXCEL', name: 'No Excel', action: 'PROHIBIT' }],
  };
  const prohibited = parseSplitModelResponse(JSON.stringify({ subPlans: [{
    id: 'sub_1', index: 1, title: 'All', planContent: 'all', prerequisites: [],
    deliverableIds: ['d.ddl', 'd.entity', 'd.mapper', 'd.service', 'd.controller', 'd.no-excel'],
  }] }), prohibitedContract);
  assert.equal(prohibited.ok, false);
  if (!prohibited.ok) assert.match(prohibited.error, /prohibited/);
});


test('type-provider dependencies require a direct or transitive prerequisite path', () => {
  const typedContract: PlanContract = {
    ...contract,
    deliverables: contract.deliverables.map((item) => {
      if (item.id === 'd.entity') return { ...item, providesTypes: ['Demo'] };
      if (item.id === 'd.mapper') return { ...item, providesTypes: ['DemoMapper'], requiresTypes: ['Demo'] };
      if (item.id === 'd.service') return { ...item, providesTypes: ['IDemoService', 'DemoServiceImpl'], requiresTypes: ['DemoMapper'] };
      if (item.id === 'd.controller') return { ...item, providesTypes: ['DemoController'], requiresTypes: ['IDemoService'] };
      return item;
    }),
  };
  const response = (mapperPrerequisites: string[], controllerPrerequisites: string[]) => JSON.stringify({
    subPlans: [
      { id: 'ddl', index: 1, title: 'DDL', planContent: 'DDL', prerequisites: [], deliverableIds: ['d.ddl'] },
      { id: 'entity', index: 2, title: 'Entity', planContent: 'Entity', prerequisites: ['ddl'], deliverableIds: ['d.entity'] },
      { id: 'mapper', index: 3, title: 'Mapper', planContent: 'Mapper', prerequisites: mapperPrerequisites, deliverableIds: ['d.mapper'] },
      { id: 'service', index: 4, title: 'Service', planContent: 'Service', prerequisites: ['mapper'], deliverableIds: ['d.service'] },
      { id: 'controller', index: 5, title: 'Controller', planContent: 'Controller', prerequisites: controllerPrerequisites, deliverableIds: ['d.controller'] },
    ],
  });

  const missing = parseSplitModelResponse(response([], ['service']), typedContract);
  assert.equal(missing.ok, false);
  if (!missing.ok) assert.match(missing.error, /without a prerequisite path/);

  const directAndTransitive = parseSplitModelResponse(response(['entity'], ['service']), typedContract);
  assert.equal(directAndTransitive.ok, true);
});


test('caller-supplied master plan identity is propagated to every validated sub-plan', () => {
  const parsed = parseSplitModelResponse(JSON.stringify({
    subPlans: [{
      id: 'sub_1', index: 1, title: 'All', planContent: 'all', prerequisites: [],
      deliverableIds: ['d.ddl', 'd.entity', 'd.mapper', 'd.service', 'd.controller'],
    }],
  }), contract, 'master-real-42');

  assert.equal(parsed.ok, true);
  if (parsed.ok) assert.equal(parsed.value.subPlans[0].masterPlanId, 'master-real-42');
});
