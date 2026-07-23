import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compilePlanContract, validatePlanContract } from './planContract';
import {
  compileStructuredPlanDraft,
  isPlanDraftGenerationBlockingIssue,
  normalizePlanDraftAgainstRequirement,
  renderStructuredPlan,
  type PlanDraftV2,
} from './planDraft';

function baseDraft(deliverables: PlanDraftV2['deliverables']): PlanDraftV2 {
  return {
    identity: {
      moduleName: 'specialperiod',
      entityName: 'SpecialPeriod',
      tableName: 'blade_special_period',
      basePackage: 'org.springblade.specialperiod',
    },
    title: 'Special period plan',
    requirementSummary: 'Manage special periods with deterministic generated artifacts.',
    fields: [
      {
        name: 'periodName',
        columnName: 'period_name',
        javaType: 'String',
        required: true,
        role: 'PERSISTENT',
        description: 'Special period name',
      },
    ],
    states: [],
    integrations: [{ type: 'API', sourceModule: 'specialperiod', entrypoint: 'SpecialPeriodController.list' }],
    deliverables,
    architectureDecisions: [],
  };
}

const duplicateTopologyDraft = baseDraft([
  { kind: 'DDL', moduleSide: 'DOC', action: 'CREATE' },
  { kind: 'ENTITY', className: 'SpecialPeriodExport', moduleSide: 'API', action: 'CREATE' },
  { kind: 'VO', className: 'SpecialPeriodVO', moduleSide: 'API', action: 'CREATE' },
  { kind: 'VO', className: 'SpecialPeriodHitVO', moduleSide: 'API', action: 'CREATE' },
  { kind: 'MAPPER', className: 'SpecialPeriodMapper', moduleSide: 'IMPL', action: 'CREATE' },
  { kind: 'SERVICE', className: 'SpecialPeriodService', moduleSide: 'IMPL', action: 'CREATE' },
  { kind: 'SERVICE', className: 'SpecialPeriodServiceImpl', moduleSide: 'IMPL', action: 'CREATE' },
  { kind: 'CONTROLLER', className: 'SpecialPeriodController', moduleSide: 'IMPL', action: 'CREATE' },
  { kind: 'EXCEL', className: 'SpecialPeriodExport', moduleSide: 'API', action: 'CREATE' },
]);

test('ungrounded model-invented state values are removed while explicitly requested values are retained', () => {
  const draft = baseDraft(duplicateTopologyDraft.deliverables);
  draft.states = [{
    name: 'appointmentStatus', values: ['PENDING', 'APPROVED'],
    transitions: [{ from: 'PENDING', to: 'APPROVED', trigger: 'approve' }], referenceField: 'status',
  }];
  draft.architectureDecisions = [{
    decision: 'Use state values PENDING and APPROVED', rationale: 'State transitions in service logic', evidence: [],
  }];

  const stripped = normalizePlanDraftAgainstRequirement(draft, 'status Integer required; no workflow');
  assert.deepEqual(stripped.states, []);
  assert.deepEqual(stripped.architectureDecisions, []);

  const retained = normalizePlanDraftAgainstRequirement(
    draft,
    'status Integer required; allowed values PENDING and APPROVED; no workflow',
  );
  assert.equal(retained.states.length, 1);
});

test('structured draft compilation collapses repeated service declarations into one closed service deliverable', () => {
  const contract = compileStructuredPlanDraft(duplicateTopologyDraft);
  const services = contract.deliverables.filter((item) => item.kind === 'SERVICE' && item.action !== 'PROHIBIT');

  assert.equal(services.length, 1);
  assert.equal(services[0].className, 'ISpecialPeriodService');
  assert.deepEqual(services[0].providesTypes, ['ISpecialPeriodService', 'SpecialPeriodServiceImpl']);
  const wrapper = contract.deliverables.find((item) => item.kind === 'WRAPPER');
  const controller = contract.deliverables.find((item) => item.kind === 'CONTROLLER');
  assert.deepEqual(wrapper?.providesTypes, ['SpecialPeriodWrapper']);
  assert.deepEqual(wrapper?.requiresTypes, ['SpecialPeriod', 'SpecialPeriodVO']);
  assert.ok(controller?.requiresTypes?.includes('ISpecialPeriodService'));
  assert.ok(controller?.requiresTypes?.includes('SpecialPeriodWrapper'));
});

test('structured draft compilation gives each VO deliverable unique type ownership', () => {
  const contract = compileStructuredPlanDraft(duplicateTopologyDraft);
  const vos = contract.deliverables.filter((item) => item.kind === 'VO' && item.action !== 'PROHIBIT');
  const canonical = vos.find((item) => item.className === 'SpecialPeriodVO');
  const custom = vos.find((item) => item.className === 'SpecialPeriodHitVO');

  assert.ok(canonical);
  assert.ok(custom);
  assert.deepEqual(canonical!.providesTypes, [
    'SpecialPeriodVO',
    'SpecialPeriodQVO',
    'SpecialPeriodIVO',
    'SpecialPeriodUVO',
  ]);
  assert.deepEqual(custom!.providesTypes, ['SpecialPeriodHitVO']);
});


test('artifact inventory prose cannot contradict the canonical deliverable type family', () => {
  const draft = baseDraft(duplicateTopologyDraft.deliverables);
  draft.architectureDecisions = [{
    decision: 'Only generate VO and QVO; do not generate EVO or Feign',
    rationale: 'Keep the DTO inventory small',
    evidence: ['requirement'],
  }];
  const contract = compileStructuredPlanDraft(draft);
  assert.equal(contract.architectureDecisions.length, 0);
  assert.deepEqual(contract.deliverables.find((item) => item.kind === 'VO')?.providesTypes, [
    'SpecialPeriodVO', 'SpecialPeriodQVO', 'SpecialPeriodIVO', 'SpecialPeriodUVO',
  ]);
});

test('structured draft compilation canonicalizes the identity entity and produces no type-topology errors', () => {
  const contract = compileStructuredPlanDraft(duplicateTopologyDraft);
  const entity = contract.deliverables.find((item) => item.kind === 'ENTITY');
  const excel = contract.deliverables.find((item) => item.kind === 'EXCEL');

  assert.equal(entity?.className, 'SpecialPeriod');
  assert.deepEqual(entity?.providesTypes, ['SpecialPeriod']);
  assert.equal(excel?.className, 'SpecialPeriodExport');
  assert.deepEqual(excel?.providesTypes, ['SpecialPeriodExport']);

  const markdown = renderStructuredPlan(duplicateTopologyDraft, contract);
  const compilation = compilePlanContract(markdown);
  const rules = new Set(validatePlanContract(
    compilation,
    { adaptationSummary: null, search: null },
    markdown,
  ).filter((issue) => issue.severity === 'ERROR').map((issue) => issue.rule));

  for (const rule of [
    'DELIVERABLE-TYPE-SHAPE-INVALID',
    'DELIVERABLE-TYPE-DUPLICATE',
    'DELIVERABLE-CLASS-NOT-PROVIDED',
    'TYPE-PROVIDER-DUPLICATE',
    'TYPE-PROVIDER-MISSING',
  ]) {
    assert.equal(rules.has(rule), false, `unexpected ${rule}`);
  }
});

test('structured draft compilation rejects unresolved cross-kind type ownership conflicts', () => {
  const conflicting = baseDraft([
    { kind: 'DDL', moduleSide: 'DOC', action: 'CREATE' },
    { kind: 'ENTITY', className: 'SpecialPeriod', moduleSide: 'API', action: 'CREATE' },
    { kind: 'VO', className: 'SpecialPeriodVO', moduleSide: 'API', action: 'CREATE' },
    { kind: 'MAPPER', className: 'SpecialPeriodMapper', moduleSide: 'IMPL', action: 'CREATE' },
    { kind: 'SERVICE', className: 'ISpecialPeriodService', moduleSide: 'IMPL', action: 'CREATE' },
    { kind: 'CONTROLLER', className: 'SpecialPeriodController', moduleSide: 'IMPL', action: 'CREATE' },
    { kind: 'EXCEL', className: 'SpecialPeriodController', moduleSide: 'API', action: 'CREATE' },
  ]);

  assert.throws(
    () => compileStructuredPlanDraft(conflicting),
    /PLAN_DRAFT_DELIVERABLE_CONFLICT: Type SpecialPeriodController/,
  );
});


test('generation gate blocks compiler and type-topology errors before a plan is emitted', () => {
  for (const rule of [
    'PLAN-CONTRACT-UNRESOLVED-REFERENCE',
    'PLAN-IDENTITY-ENTITY-MISMATCH',
    'DELIVERABLE-TYPE-SHAPE-INVALID',
    'DELIVERABLE-TYPE-DUPLICATE',
    'DELIVERABLE-CLASS-NOT-PROVIDED',
    'TYPE-PROVIDER-DUPLICATE',
    'TYPE-PROVIDER-MISSING',
  ]) {
    assert.equal(isPlanDraftGenerationBlockingIssue({ severity: 'ERROR', rule, message: rule }), true, rule);
  }
  assert.equal(isPlanDraftGenerationBlockingIssue({
    severity: 'ERROR',
    rule: 'REF-ARCHITECTURE-DECISION-MISSING',
    message: 'requires semantic review',
  }), false);
  assert.equal(isPlanDraftGenerationBlockingIssue({
    severity: 'WARN',
    rule: 'TYPE-PROVIDER-DUPLICATE',
    message: 'warning-only fixture',
  }), false);
});
