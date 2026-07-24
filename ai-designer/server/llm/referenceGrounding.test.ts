import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DEFAULT_REFERENCE_PROFILE } from '../services/referenceSummary';
import { compileConfiguredPlanDraft } from './configuredPlanDraft';
import { compilePlanContract, validateNarrativeContractConsistency, validatePlanContract } from './planContract';
import { compileStructuredPlanDraft, renderStructuredPlan } from './planDraft';
import { applyReferenceGrounding, groundPlanDraftWithReferenceEvidence } from './referenceGrounding';
import type { ReferenceReviewEvidence, ReferenceSymbol } from '../services/referenceSummary';

const enrichedRequirement = `\u4e00\u3001\u4e1a\u52a1\u9886\u57df\u7b80\u4ecb\n\u65b0\u589e\u7279\u6b8a\u65f6\u6bb5\u52a8\u706b\u4f5c\u4e1a\u5347\u7ea7\u7ba1\u7406\u3002\n\u4e8c\u3001\u4e1a\u52a1\u5b57\u6bb5\u6e05\u5355\n\u6a21\u5757\u540d: special_period\n\u5b9e\u4f53\u540d: SpecialPeriod\n\u8868\u540d: blade_special_period\n1. id Long \u662f \u4e3b\u952eID\n2. periodName String \u662f \u7279\u6b8a\u65f6\u6bb5\u540d\u79f0\n3. periodType Integer \u662f \u65f6\u6bb5\u7c7b\u578b\n4. startDate Date \u5426 \u5f00\u59cb\u65e5\u671f\n5. endDate Date \u5426 \u7ed3\u675f\u65e5\u671f\n6. upgradeLevel Integer \u662f \u5ba1\u6279\u5347\u7ea7\u7ea7\u522b\n7. status Integer \u662f \u72b6\u6001\n\u4e09\u3001\u72b6\u6001\u673a status\n\u4e94\u3001\u9700\u8981 Excel \u5bfc\u5165\u5bfc\u51fa\n2. \u9700\u8981 Feign\uff1a\u52a8\u706b\u4f5c\u4e1a\u7533\u8bf7\u670d\u52a1\u8fdc\u7a0b\u8c03\u7528 special_period \u6a21\u5757\u3002`;

function symbol(partial: Partial<ReferenceSymbol> & Pick<ReferenceSymbol, 'simpleName' | 'packageName' | 'type'>): ReferenceSymbol {
  return {
    score: 80,
    relationExpanded: false,
    module: 'safetycontrol',
    side: 'IMPL',
    relativePath: 'reference.java',
    publicMethodSignatures: [],
    fields: {},
    ...partial,
  };
}

const evidence: ReferenceReviewEvidence = {
  adaptationSummary: 'BladeX 2.4.0, Java 8, javax, Swagger v2',
  search: {
    snapshotId: 'ref-current',
    profile: DEFAULT_REFERENCE_PROFILE,
    intent: enrichedRequirement,
    anomalies: [],
    decisions: [{
      capability: 'special-period hot-work upgrade',
      decision: 'REUSE',
      targetModule: 'safetycontrol',
      confidence: 0.97,
      reason: 'Existing safety-control ownership is unique.',
      evidenceSymbols: ['org.springblade.safetycontrol.entity.WorkOrderTask'],
    }],
    relations: [],
    symbols: [
      symbol({ score: 130, simpleName: 'WorkOrderTask', packageName: 'org.springblade.safetycontrol.entity', type: 'ENTITY', side: 'API', mavenModulePath: 'blade-service-api/blade-safety-control-api' }),
      symbol({ score: 72, simpleName: 'SkRiskDatesController', packageName: 'org.springblade.safetycontrol.controller', type: 'CONTROLLER', mavenModulePath: 'blade-service/blade-safety-control',
        publicMethodSignatures: ['queryPageList(SkRiskDates,Integer,Integer,HttpServletRequest)', 'add(SkRiskDates)'] }),
      symbol({ score: 104, simpleName: 'WorkOrderTaskServiceImpl', packageName: 'org.springblade.safetycontrol.service.impl', type: 'SERVICE_IMPL',
        publicMethodSignatures: ['getTaskType(WorkOrderTask)', 'listSpecialWorkInfo(IPage,YQSpecialWorkInfoVO)'] }),
    ],
  },
};

test('unique high-confidence ownership grounds provisional module before canonical rendering', () => {
  const configured = compileConfiguredPlanDraft(enrichedRequirement, [{ type: 'ENTITY', name: 'Special period', config: {
    tableName: 'blade_special_period', moduleName: 'special_period', entityName: 'SpecialPeriod', needExcel: true,
  } }]);
  assert.ok(configured);
  const grounded = groundPlanDraftWithReferenceEvidence(configured!, evidence);
  assert.ok(grounded.grounding);
  assert.equal(grounded.draft.identity.moduleName, 'safetycontrol');
  assert.equal(grounded.draft.identity.basePackage, 'org.springblade.safetycontrol');
  assert.equal(grounded.draft.deliverables.some((item) => item.kind === 'FEIGN'), false);
  assert.doesNotMatch(grounded.draft.requirementSummary, /\\u6a21\\u5757\\u540d:\\s*special_period/);
  assert.match(grounded.draft.requirementSummary, /safetycontrol/);
  assert.match(grounded.draft.requirementSummary, /periodName/);
  assert.match(grounded.draft.requirementSummary, /upgradeLevel/);
  assert.match(grounded.draft.requirementSummary, /blade_special_period/);

  const contract = applyReferenceGrounding(
    compileStructuredPlanDraft(grounded.draft, evidence.search!.snapshotId),
    grounded.grounding,
  );
  const markdown = renderStructuredPlan(grounded.draft, contract);
  const compilation = compilePlanContract(markdown);
  const errors = validatePlanContract(compilation, evidence, markdown).filter((item) => item.severity === 'ERROR');
  assert.equal(contract.identity.apiModuleName, 'blade-safety-control-api');
  assert.equal(contract.identity.serviceModuleName, 'blade-safety-control');
  assert.equal(contract.identity.serviceName, 'blade-safety-control');
  assert.equal(contract.modules[0]?.kind, 'EXISTING');
  assert.equal(contract.modules[0]?.name, 'safetycontrol');
  assert.equal(contract.referenceBindings[0]?.planElementId, contract.modules[0]?.id);
  assert.equal(contract.referenceBindings[0]?.referenceSymbol,
    'org.springblade.safetycontrol.controller.SkRiskDatesController');
  assert.ok(contract.integrations.some((item) => item.entrypoint?.includes('WorkOrderTaskServiceImpl')));
  assert.ok(contract.integrations.some((item) => item.entrypoint?.includes('listSpecialWorkInfo')));
  assert.equal(errors.some((item) => item.rule === 'REF-DOMAIN-OWNER-CONFLICT'), false);
  assert.equal(errors.some((item) => item.rule === 'REF-DUPLICATE-CAPABILITY'), false);
  assert.equal(errors.some((item) => item.rule === 'INTEGRATION-ENTRY-MISSING'), false);
  assert.equal(validateNarrativeContractConsistency(markdown, contract).length, 0);
});

test('ambiguous or anomalous ownership evidence is not applied automatically', () => {
  const configured = compileConfiguredPlanDraft(enrichedRequirement, [{ type: 'ENTITY', name: 'Special period', config: {
    tableName: 'blade_special_period', moduleName: 'special_period', entityName: 'SpecialPeriod',
  } }]);
  assert.ok(configured);
  const ambiguous: ReferenceReviewEvidence = {
    ...evidence,
    search: {
      ...evidence.search!,
      anomalies: [{ code: 'CONFLICT', severity: 'ERROR', message: 'Conflicting ownership', evidencePath: 'pom.xml' }],
    },
  };
  const grounded = groundPlanDraftWithReferenceEvidence(configured!, ambiguous);
  assert.equal(grounded.grounding, null);
  assert.equal(grounded.draft.identity.moduleName, 'special_period');
});
