import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compileStructuredPlanDraft, renderStructuredPlan, type PlanDraftV2 } from './planDraft';
import { buildSemanticReviewSubject } from './reviewSubject';

const draft: PlanDraftV2 = {
  identity: { moduleName: 'demo', entityName: 'Demo', tableName: 'blade_demo', basePackage: 'org.springblade.demo' },
  title: 'Demo plan', requirementSummary: 'Keep this business requirement.',
  fields: [{ name: 'name', columnName: 'name', javaType: 'String', required: true, role: 'PERSISTENT', description: 'Name' }],
  states: [], integrations: [{ type: 'API', sourceModule: 'demo', entrypoint: 'DemoController.list' }],
  deliverables: [
    { kind: 'DDL', moduleSide: 'DOC', action: 'CREATE' },
    { kind: 'ENTITY', className: 'Demo', moduleSide: 'API', action: 'CREATE' },
    { kind: 'VO', className: 'DemoVO', moduleSide: 'API', action: 'CREATE' },
    { kind: 'MAPPER', className: 'DemoMapper', moduleSide: 'IMPL', action: 'CREATE' },
    { kind: 'SERVICE', className: 'IDemoService', moduleSide: 'IMPL', action: 'CREATE' },
    { kind: 'CONTROLLER', className: 'DemoController', moduleSide: 'IMPL', action: 'CREATE' },
  ], architectureDecisions: [],
};

test('semantic review subject keeps narrative and canonical JSON without duplicated rendered summaries', () => {
  const contract = compileStructuredPlanDraft(draft, 'ref-1');
  const rendered = renderStructuredPlan(draft, contract);
  const subject = buildSemanticReviewSubject(rendered, contract);
  assert.match(subject, /Keep this business requirement/);
  assert.match(subject, /Canonical semantic projection/);
  assert.match(subject, /"contractVersion":"2.0"/);
  assert.doesNotMatch(subject, /providesTypes|requiresTypes|sourceHash/);
  assert.doesNotMatch(subject, /```plan-contract/);
  assert.doesNotMatch(subject, /## Canonical implementation contract/);
  assert.ok(subject.length < rendered.length);
});
