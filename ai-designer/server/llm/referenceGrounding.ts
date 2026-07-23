import type { PlanContract } from './planContract';
import type { PlanDraftV2 } from './planDraft';
import type {
  ReferenceAccessDecision,
  ReferenceReviewEvidence,
  ReferenceSymbol,
} from '../services/referenceSummary';

const MIN_OWNERSHIP_CONFIDENCE = 0.9;

export interface AppliedReferenceGrounding {
  provisionalModuleName: string;
  targetModule: string;
  decision: 'REUSE' | 'EXTEND';
  referenceSymbol: string;
  integrationEntrypoint?: string;
  basePackage: string;
  apiModuleName: string;
  serviceModuleName: string;
  serviceName: string;
}

export interface ReferenceGroundedDraft {
  draft: PlanDraftV2;
  grounding: AppliedReferenceGrounding | null;
}

/**
 * Consumes a unique, high-confidence reference ownership decision before canonical compilation.
 * Ambiguous or anomalous evidence remains fail-closed and is left for review instead of being guessed here.
 */
export function groundPlanDraftWithReferenceEvidence(
  draft: PlanDraftV2,
  evidence: ReferenceReviewEvidence,
): ReferenceGroundedDraft {
  const search = evidence.search;
  if (!search || search.anomalies.some((item) => item.severity === 'ERROR')) {
    return { draft, grounding: null };
  }
  const decisions = search.decisions.filter(isGroundableDecision);
  if (decisions.length !== 1) return { draft, grounding: null };

  const decision = decisions[0];
  const targetModule = normalizeModuleName(decision.targetModule!);
  const provisionalModuleToken = draft.identity.moduleName.trim();
  const provisionalModuleName = normalizeModuleName(provisionalModuleToken);
  if (!targetModule || targetModule === provisionalModuleName) {
    return { draft, grounding: null };
  }

  const targetSymbols = search.symbols.filter((symbol) => normalizeModuleName(symbol.module) === targetModule);
  const bindingSymbol = selectBindingSymbol(targetSymbols, draft);
  if (!bindingSymbol) return { draft, grounding: null };
  const integration = selectIntegrationEntrypoint(targetSymbols);
  const basePackage = inferBasePackage(bindingSymbol, targetModule);
  const moduleArtifacts = inferModuleArtifactNames(targetSymbols, targetModule);
  const selfFeign = draft.integrations.some((item) => item.type === 'FEIGN')
    && draft.integrations.filter((item) => item.type === 'FEIGN').every((item) => {
      const source = normalizeModuleName(item.sourceModule ?? provisionalModuleName);
      const target = normalizeModuleName(item.targetModule ?? provisionalModuleName);
      return source === provisionalModuleName && (target === provisionalModuleName || target === targetModule);
    });

  const integrations = draft.integrations
    .filter((item) => !(selfFeign && item.type === 'FEIGN'))
    .map((item) => ({
      ...item,
      ...(item.sourceModule == null || normalizeModuleName(item.sourceModule) === provisionalModuleName
        ? { sourceModule: targetModule } : {}),
      ...(item.targetModule && normalizeModuleName(item.targetModule) === provisionalModuleName
        ? { targetModule } : {}),
    }));
  if (integration && !integrations.some((item) => item.entrypoint === integration)) {
    integrations.push({ type: 'OTHER', sourceModule: targetModule, targetModule, entrypoint: integration });
  }

  const groundedDraft: PlanDraftV2 = {
    ...draft,
    identity: { ...draft.identity, moduleName: targetModule, basePackage },
    requirementSummary: groundRequirementSummary(
      draft.requirementSummary,
      provisionalModuleToken,
      targetModule,
      integration,
      selfFeign,
    ),
    integrations,
    deliverables: selfFeign
      ? draft.deliverables.filter((item) => item.kind !== 'FEIGN')
      : draft.deliverables,
  };
  return {
    draft: groundedDraft,
    grounding: {
      provisionalModuleName,
      targetModule,
      decision: decision.decision,
      referenceSymbol: qualifiedName(bindingSymbol),
      ...(integration ? { integrationEntrypoint: integration } : {}),
      basePackage,
      ...moduleArtifacts,
    },
  };
}

export function applyReferenceGrounding(
  contract: PlanContract,
  grounding: AppliedReferenceGrounding | null,
): PlanContract {
  if (!grounding) return contract;
  const module = contract.modules.find((item) => normalizeModuleName(item.name) === grounding.targetModule)
    ?? contract.modules[0];
  if (!module) return contract;
  const moduleId = module.id;
  return {
    ...contract,
    identity: {
      ...contract.identity,
      moduleName: grounding.targetModule,
      basePackage: grounding.basePackage,
      apiModuleName: grounding.apiModuleName,
      serviceModuleName: grounding.serviceModuleName,
      serviceName: grounding.serviceName,
    },
    modules: contract.modules.map((item) => item.id === moduleId
      ? { ...item, name: grounding.targetModule, basePackage: grounding.basePackage, kind: 'EXISTING' }
      : item),
    referenceBindings: dedupeBindings([
      ...contract.referenceBindings,
      {
        id: `binding.${toId(grounding.targetModule)}.${toId(simpleName(grounding.referenceSymbol))}`,
        planElementId: moduleId,
        referenceSymbol: grounding.referenceSymbol,
        decision: grounding.decision,
        targetModule: grounding.targetModule,
      },
    ]),
  };
}

function isGroundableDecision(
  decision: ReferenceAccessDecision,
): decision is ReferenceAccessDecision & { decision: 'REUSE' | 'EXTEND'; targetModule: string } {
  return (decision.decision === 'REUSE' || decision.decision === 'EXTEND')
    && decision.confidence >= MIN_OWNERSHIP_CONFIDENCE
    && typeof decision.targetModule === 'string'
    && decision.targetModule.trim().length > 0;
}

function selectBindingSymbol(symbols: ReferenceSymbol[], draft: PlanDraftV2): ReferenceSymbol | undefined {
  const requirement = `${draft.title} ${draft.requirementSummary} ${draft.identity.entityName}`.toLowerCase();
  return [...symbols].sort((left, right) => bindingScore(right, requirement) - bindingScore(left, requirement)
    || qualifiedName(left).localeCompare(qualifiedName(right)))[0];
}

function bindingScore(symbol: ReferenceSymbol, requirement: string): number {
  const name = symbol.simpleName.toLowerCase();
  let score = symbol.score;
  if (/specialperiod|riskdates|holiday|calendar|period/.test(name)
    && /special period|holiday|calendar|\u7279\u6b8a\u65f6\u6bb5|\u8282\u5047\u65e5|\u516c\u4f11\u65e5|\u591c\u95f4/.test(requirement)) score += 180;
  if (/workordertask|hotwork|workticket/.test(name)
    && /hot work|work order|\u52a8\u706b|\u4f5c\u4e1a\u7968/.test(requirement)) score += 90;
  if (symbol.type === 'CONTROLLER' || symbol.type === 'SERVICE' || symbol.type === 'SERVICE_IMPL') score += 20;
  if (symbol.publicMethodSignatures.length > 0) score += 10;
  return score;
}

function selectIntegrationEntrypoint(symbols: ReferenceSymbol[]): string | undefined {
  const candidates = symbols.filter((symbol) =>
    symbol.publicMethodSignatures.length > 0 && ['SERVICE', 'SERVICE_IMPL', 'CONTROLLER'].includes(symbol.type));
  const selected = [...candidates].sort((left, right) => integrationScore(right) - integrationScore(left)
    || qualifiedName(left).localeCompare(qualifiedName(right)))[0];
  if (!selected) return undefined;
  const method = [...selected.publicMethodSignatures].sort((left, right) => methodScore(right) - methodScore(left)
    || left.localeCompare(right))[0];
  return method ? `${qualifiedName(selected)}.${method}` : qualifiedName(selected);
}

function integrationScore(symbol: ReferenceSymbol): number {
  const name = symbol.simpleName.toLowerCase();
  let score = symbol.score;
  if (/workordertask/.test(name)) score += 180;
  else if (/workorder/.test(name)) score += 130;
  if (/examint|approval|flow|node/.test(name)) score += 60;
  if (symbol.type === 'SERVICE_IMPL' || symbol.type === 'SERVICE') score += 40;
  else if (symbol.type === 'CONTROLLER') score += 20;
  score += Math.min(symbol.publicMethodSignatures.length, 20);
  return score;
}

function methodScore(signature: string): number {
  const methodName = signature.slice(0, signature.indexOf('(') >= 0 ? signature.indexOf('(') : signature.length).toLowerCase();
  let score = 0;
  if (/specialwork|workstate|tasktype|approve|flow|submit|upgrade/.test(methodName)) score += 120;
  if (/add|update|save|execute|trigger/.test(methodName)) score += 40;
  if (/list|analyse|detail|select/.test(methodName)) score += 20;
  return score;
}

function inferBasePackage(symbol: ReferenceSymbol, targetModule: string): string {
  const match = symbol.packageName.match(/^(org\.springblade\.[A-Za-z0-9_]+)/);
  return match?.[1] ?? `org.springblade.${targetModule.replace(/[^A-Za-z0-9]/g, '')}`;
}

function inferModuleArtifactNames(symbols: ReferenceSymbol[], targetModule: string): Pick<AppliedReferenceGrounding, 'apiModuleName' | 'serviceModuleName' | 'serviceName'> {
  const paths = symbols.map((symbol) => symbol.mavenModulePath ?? '').filter(Boolean);
  const apiModuleName = paths.map((path) => path.match(/(?:^|\/)blade-service-api\/(blade-[A-Za-z0-9-]+-api)(?:\/|$)/)?.[1])
    .find((value): value is string => Boolean(value));
  const serviceModuleName = paths.map((path) => path.match(/(?:^|\/)blade-service\/(blade-[A-Za-z0-9-]+)(?:\/|$)/)?.[1])
    .find((value): value is string => Boolean(value));
  const fallbackService = `blade-${targetModule}`;
  const resolvedService = serviceModuleName ?? fallbackService;
  return {
    apiModuleName: apiModuleName ?? `${resolvedService}-api`,
    serviceModuleName: resolvedService,
    serviceName: resolvedService,
  };
}

function groundRequirementSummary(
  summary: string,
  provisionalModuleToken: string,
  targetModule: string,
  integrationEntrypoint: string | undefined,
  selfFeign: boolean,
): string {
  const moduleVariants = Array.from(new Set([provisionalModuleToken, provisionalModuleToken.replace(/_/g, '-'), provisionalModuleToken.replace(/-/g, '_')]))
    .filter(Boolean).map(escapeRegExp).join('|');
  const modulePattern = new RegExp(`(^|[^A-Za-z0-9_])(?:${moduleVariants})(?=$|[^A-Za-z0-9_])`, 'gi');
  let grounded = summary.replace(modulePattern, (_match, prefix: string) => `${prefix}${targetModule}`);
  if (!selfFeign) return grounded;
  const replacement = `\u65e0\u9700\u8de8\u6a21\u5757 Feign\uff1a\u53c2\u8003\u80fd\u529b\u5df2\u5f52\u5c5e ${targetModule}\uff0c\u52a8\u706b\u4f5c\u4e1a\u7533\u8bf7\u901a\u8fc7\u6a21\u5757\u5185\u670d\u52a1\u5165\u53e3 ${integrationEntrypoint ?? '(reference-bound work-order service)'} \u5b8c\u6210\u7279\u6b8a\u65f6\u6bb5\u5339\u914d\u4e0e\u5ba1\u6279\u5347\u7ea7\u3002`;
  grounded = grounded.replace(/\u9700\u8981\s*Feign\s*[:\uff1a][\s\S]*$/i, replacement);
  grounded = grounded.replace(/needs?\s+Feign\s*[:\uff1a][\s\S]*$/i, replacement);
  return grounded;
}

function dedupeBindings(bindings: PlanContract['referenceBindings']): PlanContract['referenceBindings'] {
  const seen = new Set<string>();
  return bindings.filter((binding) => {
    const key = `${binding.planElementId}|${binding.referenceSymbol}|${binding.decision}|${binding.targetModule ?? ''}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function qualifiedName(symbol: ReferenceSymbol): string {
  return `${symbol.packageName}.${symbol.simpleName}`;
}
function simpleName(value: string): string { return value.slice(value.lastIndexOf('.') + 1); }
function normalizeModuleName(value: string): string {
  return value.toLowerCase().replace(/^blade-/, '').replace(/-api$/, '').replace(/_/g, '-').trim();
}
function toId(value: string): string {
  return value.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/[^A-Za-z0-9]+/g, '-').toLowerCase().replace(/^-|-$/g, '');
}
function escapeRegExp(value: string): string { return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
