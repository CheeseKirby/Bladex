import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compileConfiguredPlanDraft } from './configuredPlanDraft';

test('one-click enriched requirement compiles deterministically into a closed single-entity draft', () => {
  const input = '\u4e8c\u3001fields 1. id Long \u662f primary 2. periodName String \u662f name 3. periodType Integer \u662f type 4. startDate Date \u5426 start 5. endDate Date \u5426 end 6. upgradeLevel Integer \u662f level 7. status Integer \u662f state \u4e09\u3001\u72b6\u6001\u673a 0 -> 1 -> 2 \u4e94\u3001 Excel \u5bfc\u5165\u5bfc\u51fa Feign \u8fdc\u7a0b\u8c03\u7528';
  const draft = compileConfiguredPlanDraft(input, [{ type: 'ENTITY', name: 'Special period', config: {
    tableName: 'blade_special_period', moduleName: 'special_period', entityName: 'SpecialPeriod', needExcel: true,
  } }]);
  assert.ok(draft);
  assert.deepEqual(draft!.fields.map((field) => field.name), ['periodName', 'periodType', 'startDate', 'endDate', 'upgradeLevel']);
  assert.ok(draft!.deliverables.some((item) => item.kind === 'FEIGN'));
  assert.ok(draft!.deliverables.some((item) => item.kind === 'EXCEL'));
  assert.equal(draft!.states[0]?.referenceField, 'status');
});


test('one-click slash-delimited field inventory is compiled without falling back to the model', () => {
  const input = '\u4e8c\u3001\u4e1a\u52a1\u5b57\u6bb5\u6e05\u5355 (\u8868\u540d: blade_hot_work_upgrade \u5b9e\u4f53\u540d: HotWorkUpgrade) '
    + 'id / Long / \u662f / \u4e3b\u952eID hotWorkId / Long / \u662f / \u5173\u8054\u52a8\u706b\u4f5c\u4e1a\u7533\u8bf7\u5355ID '
    + 'originalLevel / Integer / \u662f / \u539f\u4f5c\u4e1a\u5ba1\u6279\u7ea7\u522b upgradeLevel / Integer / \u662f / \u5347\u7ea7\u540e\u4f5c\u4e1a\u5ba1\u6279\u7ea7\u522b '
    + 'triggerPeriod / String / \u662f / \u89e6\u53d1\u5347\u7ea7\u7684\u7279\u6b8a\u65f6\u6bb5\u7c7b\u578b periodConfigId / Long / \u5426 / \u547d\u4e2d\u7684\u7279\u6b8a\u65f6\u6bb5\u914d\u7f6eID '
    + 'upgradeReason / String / \u5426 / \u5347\u7ea7\u89e6\u53d1\u539f\u56e0 status / Integer / \u662f / \u5347\u7ea7\u72b6\u6001 '
    + '3. \u4e1a\u52a1\u72b6\u6001\u673a status 4. \u5173\u952e\u4e1a\u52a1\u89c4\u5219 5. \u662f\u5426\u9700\u8981 Excel/Feign';
  const draft = compileConfiguredPlanDraft(input, [{ type: 'ENTITY', name: 'Hot work upgrade', config: {
    tableName: 'blade_hot_work_upgrade', moduleName: 'hot_work_upgrade', entityName: 'HotWorkUpgrade',
  } }]);
  assert.ok(draft);
  assert.deepEqual(draft!.fields.map((field) => field.name), [
    'hotWorkId', 'originalLevel', 'upgradeLevel', 'triggerPeriod', 'periodConfigId', 'upgradeReason',
  ]);
});


test('one-click newline field inventory accepts unnumbered required and optional markers', () => {
  const input = [
    '2.\u4e1a\u52a1\u5b57\u6bb5\u6e05\u5355',
    'id Long \u5fc5\u586b \u4e3b\u952eID',
    'periodName String \u5fc5\u586b \u7279\u6b8a\u65f6\u6bb5\u540d\u79f0',
    'periodType Integer \u5fc5\u586b \u65f6\u6bb5\u7c7b\u578b',
    'startDate Date \u5fc5\u586b \u5f00\u59cb\u65e5\u671f',
    'startTime String \u9009\u586b \u6bcf\u65e5\u5f00\u59cb\u65f6\u95f4',
    'upgradeLevel Integer \u5fc5\u586b \u5ba1\u6279\u63d0\u5347\u5c42\u7ea7',
    'status Integer \u5fc5\u586b \u72b6\u6001',
    '3.\u4e1a\u52a1\u72b6\u6001\u673a',
  ].join('\n');
  const draft = compileConfiguredPlanDraft(input, [{ type: 'ENTITY', name: 'Special period', config: {
    tableName: 'blade_special_period', moduleName: 'specialperiod', entityName: 'SpecialPeriod', needExcel: true,
  } }]);
  assert.ok(draft);
  assert.deepEqual(draft!.fields.map((field) => field.name), [
    'periodName', 'periodType', 'startDate', 'startTime', 'upgradeLevel',
  ]);
  assert.equal(draft!.fields.find((field) => field.name === 'startTime')?.required, false);
});


test('explicitly disabled Excel is not reintroduced by explanatory prose', () => {
  const input = [
    'periodName / String / \u5fc5\u586b / name',
    'periodType / Integer / \u5fc5\u586b / type',
    'upgradeLevel / Integer / \u5fc5\u586b / level',
    '5. \u8f85\u52a9\u9700\u6c42: \u65e0\u9700 Excel, \u9700\u8981 Feign \u8fdc\u7a0b\u8c03\u7528',
  ].join('\n');
  const draft = compileConfiguredPlanDraft(input, [{ type: 'ENTITY', name: 'Special period', config: {
    tableName: 'blade_special_period', moduleName: 'specialperiod', entityName: 'SpecialPeriod', needExcel: false,
  } }]);
  assert.ok(draft);
  assert.equal(draft!.deliverables.some((item) => item.kind === 'EXCEL'), false);
  assert.equal(draft!.deliverables.some((item) => item.kind === 'FEIGN'), true);
});
