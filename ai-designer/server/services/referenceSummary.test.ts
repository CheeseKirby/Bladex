import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import type { PlanContract } from '../llm/planContract';
import {
  DEFAULT_REFERENCE_PROFILE,
  buildBusinessEvidence,
  buildCanonicalReferenceIntent,
  getReferenceAdaptationSummary,
  getReferenceReviewContext,
  getReferenceReviewEvidence,
  invalidateReferenceSummaryCache,
} from './referenceSummary';

test('reference summary caches successful responses and supports invalidation', async () => {
  invalidateReferenceSummaryCache();
  let calls = 0;
  const fetchMock = (async () => {
    calls += 1;
    return new Response(JSON.stringify({ data: `summary-${calls}` }), { status: 200 });
  }) as typeof fetch;

  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), 'summary-1');
  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), 'summary-1');
  assert.equal(calls, 1);

  invalidateReferenceSummaryCache();
  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), 'summary-2');
  assert.equal(calls, 2);
});

test('reference summary negative responses are cached briefly', async () => {
  invalidateReferenceSummaryCache();
  let calls = 0;
  const fetchMock = (async () => {
    calls += 1;
    return new Response('', { status: 503 });
  }) as typeof fetch;

  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), null);
  assert.equal(await getReferenceAdaptationSummary('http://part-b', fetchMock), null);
  assert.equal(calls, 1);
});

test('intent-aware context uses bounded Part B search with snapshot, relations and decisions', async () => {
  invalidateReferenceSummaryCache();
  let searchRequest: RequestInit | undefined;
  const fetchMock = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/api/project/adaptation-summary')) {
      return new Response(JSON.stringify({ data: 'framework-profile' }), { status: 200 });
    }
    if (url.endsWith('/api/project/reference/search')) {
      searchRequest = init;
      return new Response(JSON.stringify({ data: {
        snapshotId: 'ref-1234567890',
        profile: DEFAULT_REFERENCE_PROFILE,
        intent: '\u7279\u6b8a\u65f6\u6bb5\u52a8\u706b\u4f5c\u4e1a\u8282\u5047\u65e5\u5ba1\u6279\u5347\u7ea7',
        symbols: [
          {
            score: 52,
            relationExpanded: false,
            simpleName: 'WorkOrderTask',
            packageName: 'org.springblade.safetycontrol.entity',
            type: 'ENTITY',
            module: 'safetycontrol',
            side: 'API',
            mavenModulePath: 'blade-service-api/blade-safety-control-api',
            relativePath: 'blade-service-api/blade-safety-control-api/src/main/java/WorkOrderTask.java',
            tableName: 'work_order_task',
            fields: { beginTime: 'Date', endTime: 'Date', flowId: 'String', hotWorkPlan: 'String' },
            publicMethodSignatures: [],
          },
          {
            score: 20,
            relationExpanded: false,
            simpleName: 'SkRiskDates',
            packageName: 'org.springblade.safetycontrol.entity',
            type: 'ENTITY',
            module: 'safetycontrol',
            side: 'API',
            relativePath: 'src/SkRiskDates.java',
            tableName: 'sk_risk_dates',
            fields: { date: 'String', type: 'Integer' },
            publicMethodSignatures: [],
          },
        ],
        relations: [{
          source: 'org.springblade.safetycontrol.entity.WorkOrderTask',
          target: 'work_order_task',
          type: 'ENTITY_TABLE',
          evidence: 'src/WorkOrderTask.java',
        }],
        anomalies: [{
          code: 'REF-DANGLING-MODULE',
          severity: 'ERROR',
          message: 'blade-service/blade-specialperiod is declared but missing',
          evidencePath: 'blade-service/pom.xml',
        }],
        decisions: [{
          capability: '\u7279\u6b8a\u65f6\u6bb5\u52a8\u706b',
          decision: 'ARCHITECTURE_DECISION_REQUIRED',
          targetModule: 'safetycontrol',
          confidence: 0.9,
          reason: 'matching declared module is missing',
          evidenceSymbols: ['org.springblade.safetycontrol.entity.WorkOrderTask'],
        }],
      } }), { status: 200 });
    }
    return new Response('', { status: 404 });
  }) as typeof fetch;

  const requirement = '\u7279\u6b8a\u65f6\u6bb5\u52a8\u706b\u4f5c\u4e1a\u8282\u5047\u65e5\u5ba1\u6279\u5347\u7ea7';
  const context = await getReferenceReviewContext(requirement, 'http://part-b', fetchMock);
  const evidence = await getReferenceReviewEvidence(requirement, 'http://part-b', fetchMock);

  assert.equal(searchRequest?.method, 'POST');
  assert.deepEqual(JSON.parse(String(searchRequest?.body)), { intent: requirement, topK: 20, relationDepth: 2 });
  assert.equal(evidence.search?.snapshotId, 'ref-1234567890');
  assert.match(context ?? '', /framework-profile/);
  assert.match(context ?? '', /Snapshot: ref-1234567890/);
  assert.match(context ?? '', /WorkOrderTask/);
  assert.match(context ?? '', /SkRiskDates/);
  assert.match(context ?? '', /ENTITY_TABLE/);
  assert.match(context ?? '', /ARCHITECTURE_DECISION_REQUIRED/);
  assert.match(context ?? '', /REF-DANGLING-MODULE/);
});

test('reference search normalizes nullable ownership metadata from relation-expanded symbols', async () => {
  invalidateReferenceSummaryCache();
  const fetchMock = (async (input: string | URL | Request) => {
    const url = String(input);
    if (url.endsWith('/api/project/adaptation-summary')) {
      return new Response(JSON.stringify({ data: 'framework-profile' }), { status: 200 });
    }
    return new Response(JSON.stringify({ data: {
      snapshotId: 'ref-nullable-module',
      profile: DEFAULT_REFERENCE_PROFILE,
      intent: 'safe SpecialPeriod flow WORKFLOW',
      symbols: [{
        score: 0,
        relationExpanded: true,
        simpleName: 'XxlJobRegistry',
        packageName: 'com.xxl.job.admin.core.model',
        type: 'ENTITY',
        module: null,
        side: null,
        mavenModulePath: null,
        relativePath: 'xxl-job-admin/src/main/java/XxlJobRegistry.java',
        tableName: 'xxl_job_registry',
        publicMethodSignatures: [],
        fields: { id: 'Integer' },
      }],
      relations: [],
      anomalies: [],
      decisions: [{
        capability: 'safe SpecialPeriod',
        decision: 'NEW',
        targetModule: null,
        confidence: 0.35,
        reason: 'No identity-level match was found.',
        evidenceSymbols: [],
      }],
    } }), { status: 200 });
  }) as typeof fetch;

  const evidence = await getReferenceReviewEvidence('nullable-module-intent', 'http://part-b', fetchMock);

  assert.equal(evidence.searchStatus, 'SUCCESS');
  assert.equal(evidence.search?.symbols[0]?.module, '');
  assert.equal(evidence.search?.symbols[0]?.side, '');
  assert.equal(evidence.search?.symbols[0]?.relationExpanded, true);
});

test('invalid search schema fails closed without injecting malformed evidence', async () => {
  invalidateReferenceSummaryCache();
  const fetchMock = (async (input: string | URL | Request) => {
    const url = String(input);
    if (url.endsWith('/api/project/adaptation-summary')) {
      return new Response(JSON.stringify({ data: 'framework-profile' }), { status: 200 });
    }
    return new Response(JSON.stringify({ data: { snapshotId: 'ref-x', symbols: 'not-an-array' } }), { status: 200 });
  }) as typeof fetch;

  const evidence = await getReferenceReviewEvidence('hotwork', 'http://part-b', fetchMock);
  assert.equal(evidence.search, null);
  assert.equal(evidence.searchStatus, 'INVALID_RESPONSE');
  assert.equal(evidence.adaptationSummary, 'framework-profile');
});

test('reference not-configured business 404 is NOT_CONFIGURED, not a schema mismatch', async () => {
  invalidateReferenceSummaryCache();
  const fetchMock = (async (input: string | URL | Request) => {
    const url = String(input);
    if (url.endsWith('/api/project/adaptation-summary')) {
      return new Response(JSON.stringify({ data: 'framework-profile' }), { status: 200 });
    }
    // Part B returns HTTP 200 + business 404 when the reference project is not configured.
    return new Response(JSON.stringify({
      code: 404, success: false, data: null,
      msg: 'Reference project is not ready; configure and scan it first',
    }), { status: 200 });
  }) as typeof fetch;

  const evidence = await getReferenceReviewEvidence('hotwork', 'http://part-b', fetchMock);
  assert.equal(evidence.search, null);
  assert.equal(evidence.searchStatus, 'NOT_CONFIGURED');
  assert.match(evidence.searchDiagnostic ?? '', /not ready/i);
  assert.equal(evidence.adaptationSummary, 'framework-profile');
});

test('business evidence includes fields, module ownership and source path', () => {
  const evidence = buildBusinessEvidence('hotwork flow', [{
    score: 0,
    relationExpanded: false,
    simpleName: 'WorkOrderTask',
    packageName: 'org.springblade.safetycontrol.entity',
    type: 'ENTITY',
    module: 'safetycontrol',
    side: 'API',
    relativePath: 'src/WorkOrderTask.java',
    tableName: 'work_order_task',
    fields: { hotWorkPlan: 'String', flowId: 'String' },
    publicMethodSignatures: ['submit(String)'],
  }]);

  assert.match(evidence ?? '', /module=safetycontrol/);
  assert.match(evidence ?? '', /hotWorkPlan:String/);
  assert.match(evidence ?? '', /path=src\/WorkOrderTask.java/);
});


test('reference search exposes timeout and HTTP failures as structured outcomes', async () => {
  invalidateReferenceSummaryCache();
  const timeoutFetch = (async (input: string | URL | Request) => {
    if (String(input).endsWith('/api/project/adaptation-summary')) {
      return new Response(JSON.stringify({ data: 'framework-profile' }), { status: 200 });
    }
    throw new DOMException('timed out', 'TimeoutError');
  }) as typeof fetch;
  const timeout = await getReferenceReviewEvidence('timeout-intent', 'http://part-b', timeoutFetch);
  assert.equal(timeout.searchStatus, 'TIMEOUT');
  assert.equal(timeout.search, null);
  assert.match(timeout.searchDiagnostic ?? '', /timed out/i);

  invalidateReferenceSummaryCache();
  const httpFetch = (async (input: string | URL | Request) => {
    if (String(input).endsWith('/api/project/adaptation-summary')) {
      return new Response(JSON.stringify({ data: 'framework-profile' }), { status: 200 });
    }
    return new Response('unavailable', { status: 503 });
  }) as typeof fetch;
  const http = await getReferenceReviewEvidence('http-error-intent', 'http://part-b', httpFetch);
  assert.equal(http.searchStatus, 'HTTP_ERROR');
  assert.equal(http.search, null);
  assert.match(http.searchDiagnostic ?? '', /503/);
});

test('canonical reference intent is stable, normalized and bounded', () => {
  const contract = JSON.parse(readFileSync('../contracts/fixtures/canonical-plan-contract-v2.json', 'utf8')) as PlanContract;
  contract.architectureDecisions = [{
    id: 'decision.ticket', decision: 'EXTEND_EXISTING', rationale: `ticket ownership ${'x '.repeat(2_000)}`, evidence: [],
  }];
  const first = buildCanonicalReferenceIntent(contract);
  const second = buildCanonicalReferenceIntent(structuredClone(contract));
  assert.equal(first, second);
  assert.ok(first.length <= 1_500);
  assert.doesNotMatch(first, /\s{2,}/);
  assert.doesNotMatch(first, /ticket ownership/);
  assert.match(first, new RegExp(contract.identity.entityName, 'i'));
});
