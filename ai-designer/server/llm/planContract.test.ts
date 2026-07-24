import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { DEFAULT_REFERENCE_PROFILE } from '../services/referenceSummary';
import type { ReferenceReviewEvidence } from '../services/referenceSummary';
import {
  applyPlanRepairOperations,
  compilePlanContract,
  hashPlanContent,
  planSafeDeterministicRepairs,
  upsertPlanContractBlock,
  validatePlanContract,
  type PlanContract,
} from './planContract';

const riskyReference: ReferenceReviewEvidence = {
  adaptationSummary: 'profile',
  search: {
    snapshotId: 'ref-current',
    profile: DEFAULT_REFERENCE_PROFILE,
    intent: 'special period hot work approval',
    symbols: [
      {
        score: 60,
        relationExpanded: false,
        simpleName: 'WorkOrderTask',
        packageName: 'org.springblade.safetycontrol.entity',
        type: 'ENTITY',
        module: 'safetycontrol',
        side: 'API',
        relativePath: 'src/WorkOrderTask.java',
        tableName: 'work_order_task',
        publicMethodSignatures: [],
        fields: { state: 'Integer', workState: 'Integer', flowId: 'String' },
      },
      {
        score: 30,
        relationExpanded: false,
        simpleName: 'SkRiskDates',
        packageName: 'org.springblade.safetycontrol.entity',
        type: 'ENTITY',
        module: 'safetycontrol',
        side: 'API',
        relativePath: 'src/SkRiskDates.java',
        tableName: 'sk_risk_dates',
        publicMethodSignatures: [],
        fields: { date: 'LocalDate' },
      },
    ],
    relations: [],
    anomalies: [{
      code: 'REF-DANGLING-MODULE',
      severity: 'ERROR',
      message: 'blade-service/blade-specialperiod is declared but the directory is missing',
      evidencePath: 'blade-service/pom.xml',
    }],
    decisions: [{
      capability: 'special period hot work approval',
      decision: 'ARCHITECTURE_DECISION_REQUIRED',
      targetModule: 'safetycontrol',
      confidence: 0.91,
      reason: 'matching historical module is dangling',
      evidenceSymbols: [
        'org.springblade.safetycontrol.entity.WorkOrderTask',
        'org.springblade.safetycontrol.entity.SkRiskDates',
      ],
    }],
  },
};

test('legacy markdown compiles into a persisted contract and exposes deterministic reference conflicts', () => {
  const plan = `
# Special period hot work
module: hotwork
package: org.springblade.hotwork
Entity: SpecialHotWork
Table: blade_special_hot_work
state / workState approval state machine
DDL Entity VO Mapper Service Controller workflow
`;
  const compilation = compilePlanContract(plan);
  const issues = validatePlanContract(compilation, riskyReference, plan);
  const rules = new Set(issues.map((issue) => issue.rule));

  assert.equal(compilation.source, 'INFERRED');
  assert.equal(compilation.contract.modules[0]?.name, 'hotwork');
  assert.equal(compilation.contract.domains[0]?.ownerModuleIds[0], compilation.contract.modules[0]?.id);
  assert.equal(compilation.contract.aggregates[0]?.rootEntityId, compilation.contract.entities[0]?.id);
  assert.equal(compilation.contract.entities[0]?.name, 'SpecialHotWork');
  assert.ok(rules.has('REF-DANGLING-MODULE'));
  assert.ok(rules.has('REF-DOMAIN-OWNER-CONFLICT'));
  assert.ok(rules.has('REF-ARCHITECTURE-DECISION-MISSING'));
  assert.ok(rules.has('INTEGRATION-ENTRY-MISSING'));
  assert.ok(rules.has('DOMAIN-AGGREGATE-MIXED'));
  assert.ok(rules.has('STATE-OWNERSHIP-UNDEFINED'));

  const persisted = upsertPlanContractBlock(plan, compilation.contract);
  const embedded = compilePlanContract(persisted);
  assert.equal(embedded.source, 'EMBEDDED');
  assert.deepEqual(embedded.contract, compilation.contract);
});

test('closed contract with explicit binding, owner and integration clears ownership rules', () => {
  const contract: PlanContract = {
    contractVersion: '2.0',
    sourceHash: 'will-be-normalized',
    sourceMode: 'STRUCTURED',
    referenceSnapshotId: 'ref-current',
    referenceProfile: DEFAULT_REFERENCE_PROFILE,
    rulesetVersion: 'canonical-plan-v2',
    identity: { moduleName: 'safetycontrol', entityName: 'RiskDateExtension', tableName: 'sk_risk_dates', basePackage: 'org.springblade.safetycontrol', apiModuleName: 'blade-safety-control-api', serviceModuleName: 'blade-safety-control', serviceName: 'blade-safety-control' },
    fields: [
      { id: 'field.risk-date.date', entityId: 'entity.risk-date-extension', name: 'date', columnName: 'date', javaType: 'Date', required: true, role: 'PERSISTENT' },
      { id: 'field.risk-date.type', entityId: 'entity.risk-date-extension', name: 'type', columnName: 'type', javaType: 'Integer', required: true, role: 'PERSISTENT' },
    ],
    domains: [{ id: 'domain.safetycontrol', name: 'safetycontrol', ownerModuleIds: ['module.safetycontrol'] }],
    modules: [{ id: 'module.safetycontrol', name: 'safetycontrol', kind: 'EXISTING' }],
    aggregates: [{
      id: 'aggregate.risk-date-extension',
      name: 'RiskDateExtensionAggregate',
      domainId: 'domain.safetycontrol',
      rootEntityId: 'entity.risk-date-extension',
    }],
    entities: [{
      id: 'entity.risk-date-extension',
      name: 'RiskDateExtension',
      moduleId: 'module.safetycontrol',
      aggregateId: 'aggregate.risk-date-extension',
      table: 'sk_risk_dates',
      fields: ['date', 'type'],
    }],
    states: [{ id: 'state.approval', name: 'approvalState', ownerId: 'entity.risk-date-extension', values: ['PENDING', 'APPROVED'] }],
    integrations: [{
      id: 'integration.workflow',
      type: 'WORKFLOW',
      sourceModule: 'safetycontrol',
      targetModule: 'safetycontrol',
      entrypoint: 'WorkOrderController.submit',
    }],
    deliverables: [
      { id: 'd.ddl', kind: 'DDL', name: 'DDL' },
      { id: 'd.entity', kind: 'ENTITY', name: 'Entity' },
      { id: 'd.vo', kind: 'VO', name: 'VO' },
      { id: 'd.mapper', kind: 'MAPPER', name: 'Mapper' },
      { id: 'd.service', kind: 'SERVICE', name: 'Service' },
      { id: 'd.controller', kind: 'CONTROLLER', name: 'Controller' },
    ],
    referenceBindings: [{
      id: 'binding.risk-date',
      planElementId: 'entity.risk-date-extension',
      referenceSymbol: 'org.springblade.safetycontrol.entity.SkRiskDates',
      decision: 'EXTEND',
      targetModule: 'safetycontrol',
    }],
    architectureDecisions: [{
      id: 'adr.special-period',
      decision: 'Extend safety-control risk dates',
      rationale: 'Avoid parallel ownership.',
      evidence: ['ref-current'],
    }],
  };
  const plan = upsertPlanContractBlock('# Reviewed plan\nExisting safetycontrol extension.', contract);
  const safeReference: ReferenceReviewEvidence = {
    adaptationSummary: 'profile',
    search: {
      ...riskyReference.search!,
      anomalies: [],
      decisions: [{
        ...riskyReference.search!.decisions[0],
        decision: 'EXTEND',
      }],
    },
  };

  const issues = validatePlanContract(compilePlanContract(plan), safeReference, plan);
  const rules = new Set(issues.map((issue) => issue.rule));
  assert.equal(rules.has('REF-DOMAIN-OWNER-CONFLICT'), false);
  assert.equal(rules.has('REF-DUPLICATE-CAPABILITY'), false);
  assert.equal(rules.has('INTEGRATION-ENTRY-MISSING'), false);
  assert.equal(rules.has('STATE-OWNERSHIP-UNDEFINED'), false);
  assert.equal(issues.filter((issue) => issue.severity === 'ERROR').length, 0);
});

test('a NEW reference decision cannot force a standalone plan to invent an integration entrypoint', () => {
  const plan = '# Visitor appointment\nmodule: visitorappointment\nEntity: VisitorAppointment\nTable: blade_visitor_appointment\nDDL Entity VO Mapper Service Controller';
  const compilation = compilePlanContract(plan);
  const issues = validatePlanContract(compilation, {
    adaptationSummary: 'profile',
    search: {
      snapshotId: 'ref-new', intent: 'visitor appointment', symbols: [], relations: [], anomalies: [],
      profile: DEFAULT_REFERENCE_PROFILE,
      decisions: [{ capability: 'visitor appointment', decision: 'NEW', targetModule: 'safetycontrol', confidence: 0.35,
        reason: 'No ownership match', evidenceSymbols: [] }],
    },
  }, plan);
  assert.equal(issues.some((issue) => issue.rule === 'INTEGRATION-ENTRY-MISSING'), false);
});

test('malformed embedded contract is never trusted as canonical', () => {
  const compilation = compilePlanContract('# plan\n```plan-contract\n{"contractVersion":"1.0","modules":"bad"}\n```');
  assert.equal(compilation.source, 'INFERRED');
  assert.match(compilation.diagnostics[0], /schema validation/);
});

test('typed repair batch closes deterministic errors without free-form chapter replacement', () => {
  const plan = `
# Special period hot work
module: hotwork
package: org.springblade.hotwork
Entity: SpecialHotWork
Table: blade_special_hot_work
state / workState approval state machine
DDL Entity VO Mapper Service Controller workflow
`;
  const compilation = compilePlanContract(plan);
  const entityId = compilation.contract.entities[0].id;
  const stateId = compilation.contract.states[0].id;
  const repaired = applyPlanRepairOperations(compilation.contract, [
    {
      operation: 'RENAME_ENTITY',
      targetId: entityId,
      arguments: { name: 'RiskDateExtension' },
      resolves: ['DOMAIN-AGGREGATE-MIXED'],
      preconditions: [`${entityId} exists`],
    },
    {
      operation: 'CHANGE_STATE_OWNER',
      targetId: stateId,
      arguments: { ownerId: entityId },
      resolves: ['STATE-OWNERSHIP-UNDEFINED', 'STATE-SEMANTIC-CONFLICT'],
      preconditions: [`${stateId} exists`, `${entityId} exists`],
    },
    {
      operation: 'ADD_INTEGRATION',
      arguments: { type: 'WORKFLOW', sourceModule: 'hotwork', targetModule: 'safetycontrol', entrypoint: 'WorkOrderController.submit' },
      resolves: ['INTEGRATION-ENTRY-MISSING'],
      preconditions: ['WorkOrderController exists in reference evidence'],
    },
    {
      operation: 'DECLARE_ARCHITECTURE_DECISION',
      arguments: { decision: 'Extend safetycontrol through a guarded integration', rationale: 'Historical specialperiod module is dangling.', evidence: ['ref-current'] },
      resolves: ['REF-ARCHITECTURE-DECISION-MISSING', 'REF-DOMAIN-OWNER-CONFLICT'],
      preconditions: ['ref-current is available'],
    },
  ]);

  assert.equal(repaired.rejected.length, 0);
  assert.equal(repaired.applied.length, 4);
  const repairedPlan = upsertPlanContractBlock(plan, repaired.contract);
  const issues = validatePlanContract(compilePlanContract(repairedPlan), riskyReference, repairedPlan);
  assert.equal(issues.filter((issue) => issue.severity === 'ERROR').length, 0);
  assert.ok(issues.some((issue) => issue.rule === 'REF-DANGLING-MODULE' && issue.severity === 'WARN'));
});

test('typed repair engine rejects invalid targets without corrupting the contract', () => {
  const compilation = compilePlanContract('module: demo\nEntity: Demo\nDDL Entity Mapper Service Controller');
  const repaired = applyPlanRepairOperations(compilation.contract, [{
    operation: 'CHANGE_STATE_OWNER',
    targetId: 'state.missing',
    arguments: { ownerId: 'entity.demo' },
    resolves: ['STATE-OWNERSHIP-UNDEFINED'],
    preconditions: [],
  }]);
  assert.equal(repaired.applied.length, 0);
  assert.equal(repaired.rejected.length, 1);
  assert.deepEqual(repaired.contract, compilation.contract);
});
test('duplicate contract ids remain visible to deterministic validation', () => {
  const compilation = compilePlanContract('module: demo\nEntity: Demo\nDDL Entity Mapper Service Controller');
  const duplicate = structuredClone(compilation.contract);
  duplicate.domains.push({ ...duplicate.domains[0], name: 'duplicate-domain' });
  const plan = upsertPlanContractBlock('# duplicate contract', duplicate);
  const issues = validatePlanContract(compilePlanContract(plan), { adaptationSummary: null, search: null }, plan);
  assert.ok(issues.some((issue) => issue.rule === 'PLAN-CONTRACT-DUPLICATE-ID' && issue.severity === 'ERROR'));
});
test('safe deterministic planner repairs only mechanically provable issues', () => {
  const plan = `module: hotwork\nEntity: SpecialHotWork\nstate workState\nDDL Entity Mapper Service Controller`;
  const compilation = compilePlanContract(plan);
  const issues = validatePlanContract(compilation, riskyReference, plan);
  const repairs = planSafeDeterministicRepairs(compilation.contract, issues, riskyReference);

  assert.ok(repairs.some((repair) => repair.operation === 'CHANGE_STATE_OWNER'));
  assert.equal(repairs.some((repair) => repair.operation === 'DECLARE_ARCHITECTURE_DECISION'), false);
  assert.equal(repairs.some((repair) => repair.operation === 'MOVE_ENTITY'), false);

  const repaired = applyPlanRepairOperations(compilation.contract, repairs);
  const repairedPlan = upsertPlanContractBlock(plan, repaired.contract);
  const after = validatePlanContract(compilePlanContract(repairedPlan), riskyReference, repairedPlan);
  assert.ok(after.filter((issue) => issue.severity === 'ERROR').length
    < issues.filter((issue) => issue.severity === 'ERROR').length);
  assert.ok(after.some((issue) => issue.rule === 'REF-ARCHITECTURE-DECISION-MISSING'));
});

test('deterministic validation blocks missing, duplicate and className-mismatched type providers', () => {
  const compilation = compilePlanContract('module: demo\nEntity: Demo\nDDL Entity VO Mapper Service Controller');
  const broken = structuredClone(compilation.contract);
  const entity = broken.deliverables.find((item) => item.kind === 'ENTITY');
  const service = broken.deliverables.find((item) => item.kind === 'SERVICE');
  const controller = broken.deliverables.find((item) => item.kind === 'CONTROLLER');
  assert.ok(entity && service && controller);
  entity!.providesTypes = ['WrongEntity'];
  entity!.className = 'Demo';
  service!.providesTypes = ['SharedService'];
  controller!.providesTypes = ['SharedService'];
  controller!.requiresTypes = ['MissingBusinessType'];
  const plan = upsertPlanContractBlock('# broken type graph', broken);
  const issues = validatePlanContract(compilePlanContract(plan), { adaptationSummary: null, search: null }, plan);
  const rules = new Set(issues.map((issue) => issue.rule));
  assert.ok(rules.has('DELIVERABLE-CLASS-NOT-PROVIDED'));
  assert.ok(rules.has('TYPE-PROVIDER-DUPLICATE'));
  assert.ok(rules.has('TYPE-PROVIDER-MISSING'));
});


test('multiple plan-contract blocks are rejected and stripped consistently for content hashing', () => {
  const content = readFileSync('../contracts/fixtures/multiple-plan-contract-blocks.md', 'utf8');
  const expected = readFileSync('../contracts/fixtures/multiple-plan-contract-blocks.sha256', 'utf8').trim();
  const compilation = compilePlanContract(content);
  assert.equal(compilation.source, 'INFERRED');
  assert.match(compilation.diagnostics.join(' '), /Multiple plan-contract blocks are forbidden/);
  const lfContent = content.replace(/\r\n?/g, '\n');
  const crlfContent = lfContent.replace(/\n/g, '\r\n');
  assert.equal(hashPlanContent(lfContent), expected);
  assert.equal(hashPlanContent(crlfContent), expected);
});


test('safe deterministic repair normalizes legacy structured deliverable topology', () => {
  const seed = compilePlanContract('module: specialperiod\nEntity: SpecialPeriod\nDDL Entity VO Mapper Service Controller');
  const broken = structuredClone(seed.contract);
  broken.sourceMode = 'STRUCTURED';
  broken.identity.entityName = 'SpecialPeriod';
  const entity = broken.deliverables.find((item) => item.kind === 'ENTITY');
  const vo = broken.deliverables.find((item) => item.kind === 'VO');
  const service = broken.deliverables.find((item) => item.kind === 'SERVICE');
  const controller = broken.deliverables.find((item) => item.kind === 'CONTROLLER');
  assert.ok(entity && vo && service && controller);

  entity!.name = 'SpecialPeriodExport';
  entity!.className = 'SpecialPeriodExport';
  entity!.providesTypes = ['SpecialPeriodExport'];
  vo!.className = 'SpecialPeriodVO';
  vo!.providesTypes = ['SpecialPeriodVO', 'SpecialPeriodQVO', 'SpecialPeriodIVO', 'SpecialPeriodUVO', 'SpecialPeriodEVO'];
  broken.deliverables.push({
    ...vo!,
    id: 'deliverable.vo.custom',
    name: 'SpecialPeriodHitVO',
    className: 'SpecialPeriodHitVO',
  });
  service!.name = 'SpecialPeriodService';
  service!.className = 'SpecialPeriodService';
  service!.providesTypes = ['SpecialPeriodService', 'SpecialPeriodServiceImpl'];
  broken.deliverables.push({
    ...service!,
    id: 'deliverable.service.impl',
    name: 'SpecialPeriodServiceImpl',
    className: 'SpecialPeriodServiceImpl',
    providesTypes: ['SpecialPeriodServiceImpl'],
  });
  broken.deliverables.push({
    id: 'deliverable.excel.export',
    kind: 'EXCEL',
    name: 'SpecialPeriodExport',
    className: 'SpecialPeriodExport',
    moduleSide: 'API',
    action: 'CREATE',
    providesTypes: ['SpecialPeriodExport'],
    requiresTypes: ['SpecialPeriod'],
  });

  const plan = upsertPlanContractBlock('# saved structured plan', broken);
  const beforeCompilation = compilePlanContract(plan);
  const reference = { adaptationSummary: null, search: null };
  const before = validatePlanContract(beforeCompilation, reference, plan);
  assert.ok(before.some((issue) => issue.rule === 'TYPE-PROVIDER-DUPLICATE'));
  assert.ok(before.some((issue) => issue.rule === 'DELIVERABLE-TYPE-SHAPE-INVALID'));

  const repairs = planSafeDeterministicRepairs(beforeCompilation.contract, before, reference);
  const topologyRepair = repairs.find((repair) => repair.operation === 'NORMALIZE_DELIVERABLE_TOPOLOGY');
  assert.ok(topologyRepair);
  const result = applyPlanRepairOperations(beforeCompilation.contract, [topologyRepair!]);
  assert.equal(result.rejected.length, 0);
  assert.equal(result.applied.length, 1);

  const repairedPlan = upsertPlanContractBlock('# saved structured plan', result.contract);
  const repairedCompilation = compilePlanContract(repairedPlan);
  const after = validatePlanContract(repairedCompilation, reference, repairedPlan);
  const topologyRules = new Set(after.filter((issue) => issue.severity === 'ERROR').map((issue) => issue.rule));
  for (const rule of [
    'DELIVERABLE-TYPE-SHAPE-INVALID',
    'DELIVERABLE-TYPE-DUPLICATE',
    'DELIVERABLE-CLASS-NOT-PROVIDED',
    'TYPE-PROVIDER-DUPLICATE',
    'TYPE-PROVIDER-MISSING',
  ]) {
    assert.equal(topologyRules.has(rule), false, `unexpected ${rule}`);
  }
  const services = result.contract.deliverables.filter((item) => item.kind === 'SERVICE' && item.action !== 'PROHIBIT');
  assert.equal(services.length, 1);
  assert.deepEqual(services[0].providesTypes, ['ISpecialPeriodService', 'SpecialPeriodServiceImpl']);
  assert.deepEqual(
    result.contract.deliverables.find((item) => item.className === 'SpecialPeriodHitVO')?.providesTypes,
    ['SpecialPeriodHitVO'],
  );
});
