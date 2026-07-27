import type { PlanContract } from '../llm/planContract';
const POSITIVE_TTL_MS = 60_000;
const NEGATIVE_TTL_MS = 10_000;
const FETCH_TIMEOUT_MS = 5_000;
const REFERENCE_SEARCH_TIMEOUT_MS = Math.max(5_000, Number(process.env.BFF_REFERENCE_SEARCH_TIMEOUT_MS || 10_000));
const MAX_REFERENCE_SYMBOLS = 30;
const MAX_REFERENCE_RELATIONS = 40;
const MAX_REFERENCE_ANOMALIES = 20;
const MAX_SEARCH_CACHE_ENTRIES = 100;

export interface ReferenceFrameworkProfile {
  bladeXVersion: string;
  javaVersion: string;
  parentGroupId: string;
  apiParentArtifactId: string;
  serviceParentArtifactId: string;
  apiParentVersion: string;
  serviceParentVersion: string;
  internalDependencyVersion: string;
  validationNamespace: string;
  swaggerGeneration: string;
  entityPackageSuffix: string;
  voPackageSuffixes: Record<string, string>;
  controllerPackageSuffix: string;
  servicePackageSuffix: string;
  serviceImplPackageSuffix: string;
  mapperPackageSuffix: string;
  wrapperPackageSuffix: string;
  feignPackageSuffix: string;
  excelPackageSuffix: string;
  mapperXmlInJava: boolean;
  applicationStyle: string;
  nacosNamespace: string;
  profileStyle: string;
}

export const DEFAULT_REFERENCE_PROFILE: ReferenceFrameworkProfile = {
  bladeXVersion: '4.1.0.RELEASE', javaVersion: '17', parentGroupId: 'org.springblade',
  apiParentArtifactId: 'blade-service-api', serviceParentArtifactId: 'blade-service',
  apiParentVersion: '${revision}', serviceParentVersion: '${revision}',
  internalDependencyVersion: '${bladex.project.version}', validationNamespace: 'jakarta',
  swaggerGeneration: 'v3', entityPackageSuffix: 'pojo.entity',
  voPackageSuffixes: { VO: 'pojo.vo', QVO: 'pojo.vo', IVO: 'pojo.vo', UVO: 'pojo.vo', EVO: 'pojo.vo' },
  controllerPackageSuffix: 'controller', servicePackageSuffix: 'service',
  serviceImplPackageSuffix: 'service.impl', mapperPackageSuffix: 'mapper',
  wrapperPackageSuffix: 'wrapper', feignPackageSuffix: 'feign', excelPackageSuffix: 'excel',
  mapperXmlInJava: true, applicationStyle: 'BLADE_CLOUD_APPLICATION', nacosNamespace: 'blade',
  profileStyle: 'SPRING_CONFIG_ACTIVATE',
};

export interface ReferenceSymbol {
  score: number;
  relationExpanded: boolean;
  simpleName: string;
  packageName: string;
  type: string;
  module: string;
  side: string;
  mavenModulePath?: string;
  relativePath: string;
  tableName?: string;
  publicMethodSignatures: string[];
  fields: Record<string, string>;
}

export interface ReferenceRelation {
  source: string;
  target: string;
  type: string;
  evidence: string;
}

export interface ReferenceAnomaly {
  code: string;
  severity: 'ERROR' | 'WARN';
  message: string;
  evidencePath: string;
}

export type ReferenceAccessDecisionType =
  | 'REUSE'
  | 'EXTEND'
  | 'NEW'
  | 'ARCHITECTURE_DECISION_REQUIRED';

export interface ReferenceAccessDecision {
  capability: string;
  decision: ReferenceAccessDecisionType;
  targetModule?: string;
  confidence: number;
  reason: string;
  evidenceSymbols: string[];
}

export interface ReferenceSearchResult {
  snapshotId: string;
  profile: ReferenceFrameworkProfile;
  intent: string;
  symbols: ReferenceSymbol[];
  relations: ReferenceRelation[];
  anomalies: ReferenceAnomaly[];
  decisions: ReferenceAccessDecision[];
}

export type ReferenceSearchStatus = 'SUCCESS' | 'TIMEOUT' | 'HTTP_ERROR' | 'INVALID_RESPONSE' | 'NETWORK_ERROR' | 'NOT_CONFIGURED';

export interface ReferenceSearchOutcome {
  status: ReferenceSearchStatus;
  result: ReferenceSearchResult | null;
  durationMs: number;
  diagnostic?: string;
}

export interface ReferenceReviewEvidence {
  adaptationSummary: string | null;
  search: ReferenceSearchResult | null;
  searchStatus?: ReferenceSearchStatus;
  searchDurationMs?: number;
  searchDiagnostic?: string;
}

let adaptationCached: { value: string | null; expiresAt: number } | null = null;
let adaptationInFlight: Promise<string | null> | null = null;
const searchCache = new Map<string, { value: ReferenceSearchOutcome; expiresAt: number }>();
const searchInFlight = new Map<string, Promise<ReferenceSearchOutcome>>();

export function invalidateReferenceSummaryCache(): void {
  adaptationCached = null;
  adaptationInFlight = null;
  searchCache.clear();
  searchInFlight.clear();
}

export async function getReferenceAdaptationSummary(
  partBUrl = process.env.PART_B_URL || 'http://localhost:8111',
  fetchImpl: typeof fetch = fetch,
): Promise<string | null> {
  const now = Date.now();
  if (adaptationCached && adaptationCached.expiresAt > now) return adaptationCached.value;
  if (adaptationInFlight) return adaptationInFlight;

  adaptationInFlight = (async () => {
    let value: string | null = null;
    try {
      const response = await fetchImpl(`${partBUrl.replace(/\/+$/, '')}/api/project/adaptation-summary`, {
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
      if (response.ok) {
        const body = await response.json() as { data?: unknown };
        value = typeof body.data === 'string' && body.data.trim() ? body.data : null;
      }
    } catch {
      value = null;
    }
    adaptationCached = {
      value,
      expiresAt: Date.now() + (value ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS),
    };
    return value;
  })().finally(() => {
    adaptationInFlight = null;
  });
  return adaptationInFlight;
}

export async function getReferenceReviewEvidence(
  planOrRequirement: string,
  partBUrl = process.env.PART_B_URL || 'http://localhost:8111',
  fetchImpl: typeof fetch = fetch,
): Promise<ReferenceReviewEvidence> {
  const [adaptationSummary, searchOutcome] = await Promise.all([
    getReferenceAdaptationSummary(partBUrl, fetchImpl),
    searchReferenceProject(planOrRequirement, partBUrl, fetchImpl),
  ]);
  return {
    adaptationSummary,
    search: searchOutcome.result,
    searchStatus: searchOutcome.status,
    searchDurationMs: searchOutcome.durationMs,
    ...(searchOutcome.diagnostic ? { searchDiagnostic: searchOutcome.diagnostic } : {}),
  };
}

export async function getReferenceReviewContext(
  planOrRequirement: string,
  partBUrl = process.env.PART_B_URL || 'http://localhost:8111',
  fetchImpl: typeof fetch = fetch,
): Promise<string | null> {
  return formatReferenceReviewEvidence(
    await getReferenceReviewEvidence(planOrRequirement, partBUrl, fetchImpl),
  );
}

export interface ReferenceEvidenceFormatLimits {
  maxSymbols?: number;
  maxRelations?: number;
  maxAnomalies?: number;
}

export function formatReferenceReviewEvidence(
  evidence: ReferenceReviewEvidence,
  limits: ReferenceEvidenceFormatLimits = {},
): string | null {
  const sections = [
    evidence.adaptationSummary,
    evidence.search ? formatReferenceSearchResult(evidence.search, limits) : null,
    (evidence.searchStatus ?? (evidence.search ? 'SUCCESS' : 'INVALID_RESPONSE')) !== 'SUCCESS'
      ? `Reference search unavailable: status=${evidence.searchStatus ?? 'INVALID_RESPONSE'}, durationMs=${evidence.searchDurationMs ?? 0}${evidence.searchDiagnostic ? `, diagnostic=${evidence.searchDiagnostic}` : ''}`
      : null,
  ].filter((value): value is string => Boolean(value?.trim()));
  return sections.length > 0 ? sections.join('\n\n') : null;
}

async function searchReferenceProject(
  query: string,
  partBUrl: string,
  fetchImpl: typeof fetch,
): Promise<ReferenceSearchOutcome> {
  const normalizedIntent = normalizeSearchIntent(query);
  const cacheKey = `${partBUrl.replace(/\/+$/, '')}|${normalizedIntent}`;
  const now = Date.now();
  const cached = searchCache.get(cacheKey);
  if (cached && cached.expiresAt > now) return cached.value;
  const running = searchInFlight.get(cacheKey);
  if (running) return running;

  const promise = (async () => {
    const startedAt = Date.now();
    let outcome: ReferenceSearchOutcome;
    try {
      const response = await fetchImpl(`${partBUrl.replace(/\/+$/, '')}/api/project/reference/search`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ intent: normalizedIntent, topK: 20, relationDepth: 2 }),
        signal: AbortSignal.timeout(REFERENCE_SEARCH_TIMEOUT_MS),
      });
      if (!response.ok) {
        outcome = { status: 'HTTP_ERROR', result: null, durationMs: Date.now() - startedAt,
          diagnostic: `Part B reference search returned HTTP ${response.status}` };
      } else {
        const body = await response.json() as { code?: number; success?: boolean; data?: unknown; msg?: string };
        if (body.success === false) {
          // Part B wraps errors as HTTP 200 + { code, success:false, data:null, msg }.
          // Business 404 means the reference project is not configured/ready;
          // classify as graceful NOT_CONFIGURED instead of a schema mismatch.
          const businessCode = typeof body.code === 'number' ? body.code : -1;
          const businessMsg = typeof body.msg === 'string' ? body.msg : '';
          outcome = businessCode === 404
            ? { status: 'NOT_CONFIGURED', result: null, durationMs: Date.now() - startedAt,
                diagnostic: businessMsg || 'Reference project is not ready' }
            : { status: 'HTTP_ERROR', result: null, durationMs: Date.now() - startedAt,
                diagnostic: `Part B reference search returned business error code=${businessCode}${businessMsg ? `: ${businessMsg}` : ''}` };
        } else {
          const result = parseReferenceSearchResult(body.data);
          outcome = result
            ? { status: 'SUCCESS', result, durationMs: Date.now() - startedAt }
            : { status: 'INVALID_RESPONSE', result: null, durationMs: Date.now() - startedAt,
                diagnostic: 'Part B reference search response did not match the required schema' };
        }
      }
    } catch (error) {
      const name = error && typeof error === 'object' && 'name' in error ? String((error as { name?: unknown }).name) : '';
      const timedOut = name === 'TimeoutError' || name === 'AbortError';
      outcome = {
        status: timedOut ? 'TIMEOUT' : 'NETWORK_ERROR',
        result: null,
        durationMs: Date.now() - startedAt,
        diagnostic: error instanceof Error ? error.message : String(error),
      };
    }
    putSearchCache(cacheKey, outcome);
    return outcome;
  })().finally(() => {
    searchInFlight.delete(cacheKey);
  });
  searchInFlight.set(cacheKey, promise);
  return promise;
}

function putSearchCache(key: string, value: ReferenceSearchOutcome): void {
  if (searchCache.size >= MAX_SEARCH_CACHE_ENTRIES && !searchCache.has(key)) {
    const oldest = searchCache.keys().next().value as string | undefined;
    if (oldest) searchCache.delete(oldest);
  }
  searchCache.set(key, {
    value,
    expiresAt: Date.now() + (value.status === 'SUCCESS' ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS),
  });
}

function parseReferenceSearchResult(value: unknown): ReferenceSearchResult | null {
  if (!isRecord(value) || !nonBlankString(value.snapshotId) || typeof value.intent !== 'string') return null;
  const profile = parseReferenceFrameworkProfile(value.profile);
  if (!profile) return null;
  if (!Array.isArray(value.symbols) || !Array.isArray(value.relations)
    || !Array.isArray(value.anomalies) || !Array.isArray(value.decisions)) return null;

  const symbols = value.symbols.map(parseReferenceSymbol);
  const relations = value.relations.map(parseReferenceRelation);
  const anomalies = value.anomalies.map(parseReferenceAnomaly);
  const decisions = value.decisions.map(parseReferenceDecision);
  if (symbols.some((entry) => !entry) || relations.some((entry) => !entry)
    || anomalies.some((entry) => !entry) || decisions.some((entry) => !entry)) return null;

  return {
    snapshotId: value.snapshotId,
    profile,
    intent: value.intent,
    symbols: symbols as ReferenceSymbol[],
    relations: relations as ReferenceRelation[],
    anomalies: anomalies as ReferenceAnomaly[],
    decisions: decisions as ReferenceAccessDecision[],
  };
}


export function parseReferenceFrameworkProfile(value: unknown): ReferenceFrameworkProfile | null {
  if (!isRecord(value) || typeof value.mapperXmlInJava !== 'boolean' || !isStringRecord(value.voPackageSuffixes)) return null;
  const stringFields = [
    'bladeXVersion', 'javaVersion', 'parentGroupId', 'apiParentArtifactId', 'serviceParentArtifactId',
    'apiParentVersion', 'serviceParentVersion', 'internalDependencyVersion', 'validationNamespace',
    'swaggerGeneration', 'entityPackageSuffix', 'controllerPackageSuffix', 'servicePackageSuffix',
    'serviceImplPackageSuffix', 'mapperPackageSuffix', 'wrapperPackageSuffix', 'feignPackageSuffix',
    'excelPackageSuffix', 'applicationStyle', 'nacosNamespace', 'profileStyle',
  ] as const;
  if (stringFields.some((field) => !nonBlankString(value[field]))) return null;
  return {
    bladeXVersion: value.bladeXVersion as string,
    javaVersion: value.javaVersion as string,
    parentGroupId: value.parentGroupId as string,
    apiParentArtifactId: value.apiParentArtifactId as string,
    serviceParentArtifactId: value.serviceParentArtifactId as string,
    apiParentVersion: value.apiParentVersion as string,
    serviceParentVersion: value.serviceParentVersion as string,
    internalDependencyVersion: value.internalDependencyVersion as string,
    validationNamespace: value.validationNamespace as string,
    swaggerGeneration: value.swaggerGeneration as string,
    entityPackageSuffix: value.entityPackageSuffix as string,
    voPackageSuffixes: { ...value.voPackageSuffixes },
    controllerPackageSuffix: value.controllerPackageSuffix as string,
    servicePackageSuffix: value.servicePackageSuffix as string,
    serviceImplPackageSuffix: value.serviceImplPackageSuffix as string,
    mapperPackageSuffix: value.mapperPackageSuffix as string,
    wrapperPackageSuffix: value.wrapperPackageSuffix as string,
    feignPackageSuffix: value.feignPackageSuffix as string,
    excelPackageSuffix: value.excelPackageSuffix as string,
    mapperXmlInJava: value.mapperXmlInJava,
    applicationStyle: value.applicationStyle as string,
    nacosNamespace: value.nacosNamespace as string,
    profileStyle: value.profileStyle as string,
  };
}

function parseReferenceSymbol(value: unknown): ReferenceSymbol | null {
  if (!isRecord(value) || typeof value.score !== 'number' || typeof value.relationExpanded !== 'boolean'
    || !nonBlankString(value.simpleName) || !nullableString(value.packageName)
    || !nonBlankString(value.type) || !nullableString(value.module) || !nullableString(value.side)
    || !nonBlankString(value.relativePath) || !Array.isArray(value.publicMethodSignatures)
    || !value.publicMethodSignatures.every((entry) => typeof entry === 'string') || !isStringRecord(value.fields)) return null;
  if (value.mavenModulePath != null && typeof value.mavenModulePath !== 'string') return null;
  if (value.tableName != null && typeof value.tableName !== 'string') return null;
  return {
    score: value.score,
    relationExpanded: value.relationExpanded,
    simpleName: value.simpleName,
    packageName: value.packageName ?? '',
    type: value.type,
    module: value.module ?? '',
    side: value.side ?? '',
    mavenModulePath: value.mavenModulePath as string | undefined,
    relativePath: value.relativePath,
    tableName: value.tableName as string | undefined,
    publicMethodSignatures: value.publicMethodSignatures,
    fields: value.fields,
  };
}

function parseReferenceRelation(value: unknown): ReferenceRelation | null {
  return isRecord(value) && nonBlankString(value.source) && nonBlankString(value.target)
    && nonBlankString(value.type) && typeof value.evidence === 'string'
    ? { source: value.source, target: value.target, type: value.type, evidence: value.evidence }
    : null;
}

function parseReferenceAnomaly(value: unknown): ReferenceAnomaly | null {
  return isRecord(value) && nonBlankString(value.code)
    && (value.severity === 'ERROR' || value.severity === 'WARN')
    && nonBlankString(value.message) && typeof value.evidencePath === 'string'
    ? { code: value.code, severity: value.severity, message: value.message, evidencePath: value.evidencePath }
    : null;
}

function parseReferenceDecision(value: unknown): ReferenceAccessDecision | null {
  if (!isRecord(value) || typeof value.capability !== 'string' || !isReferenceDecision(value.decision)
    || typeof value.confidence !== 'number' || !nonBlankString(value.reason)
    || !Array.isArray(value.evidenceSymbols) || !value.evidenceSymbols.every((entry) => typeof entry === 'string')) return null;
  if (value.targetModule != null && typeof value.targetModule !== 'string') return null;
  return {
    capability: value.capability,
    decision: value.decision,
    targetModule: value.targetModule as string | undefined,
    confidence: value.confidence,
    reason: value.reason,
    evidenceSymbols: value.evidenceSymbols,
  };
}

function formatReferenceSearchResult(
  result: ReferenceSearchResult, limits: ReferenceEvidenceFormatLimits = {},
): string {
  const lines = [
    '== Intent-scoped reference evidence ==',
    `Snapshot: ${result.snapshotId}`,
    `Intent: ${result.intent || '(empty)'}`,
    'The evidence below is authoritative for ownership checks. REUSE/EXTEND decisions require explicit bindings in the plan.',
  ];
  for (const decision of result.decisions) {
    lines.push([
      `- decision=${decision.decision}`,
      decision.targetModule ? `targetModule=${decision.targetModule}` : '',
      `confidence=${decision.confidence}`,
      `reason=${decision.reason}`,
      decision.evidenceSymbols.length ? `evidence=${decision.evidenceSymbols.join(',')}` : '',
    ].filter(Boolean).join(' | '));
  }
  if (result.anomalies.length > 0) {
    lines.push('Reference anomalies:');
    for (const anomaly of result.anomalies.slice(0, limits.maxAnomalies ?? MAX_REFERENCE_ANOMALIES)) {
      lines.push(`- ${anomaly.severity} ${anomaly.code}: ${anomaly.message} | evidence=${anomaly.evidencePath}`);
    }
  }
  if (result.symbols.length > 0) {
    lines.push('Reference symbols:');
    for (const symbol of result.symbols.slice(0, limits.maxSymbols ?? MAX_REFERENCE_SYMBOLS)) {
      const fields = Object.entries(symbol.fields).slice(0, 20).map(([name, type]) => `${name}:${type}`);
      const methods = symbol.publicMethodSignatures.slice(0, 10);
      lines.push([
        `- score=${symbol.score}`,
        symbol.relationExpanded ? 'relationExpanded=true' : '',
        `[${symbol.type}]`,
        symbol.simpleName,
        `module=${symbol.module || '(unknown)'}`,
        `side=${symbol.side || '(unknown)'}`,
        symbol.tableName ? `table=${symbol.tableName}` : '',
        symbol.packageName ? `package=${symbol.packageName}` : '',
        symbol.mavenModulePath ? `maven=${symbol.mavenModulePath}` : '',
        `path=${symbol.relativePath}`,
        fields.length > 0 ? `fields=${fields.join(',')}` : '',
        methods.length > 0 ? `methods=${methods.join(',')}` : '',
      ].filter(Boolean).join(' | '));
    }
  }
  if (result.relations.length > 0) {
    lines.push('Reference relations:');
    for (const relation of result.relations.slice(0, limits.maxRelations ?? MAX_REFERENCE_RELATIONS)) {
      lines.push(`- ${relation.type}: ${relation.source} -> ${relation.target} | evidence=${relation.evidence}`);
    }
  }
  return lines.join('\n');
}

/** Legacy pure helper retained for focused scoring tests and offline callers. */
export function buildBusinessEvidence(query: string, symbols: Array<Partial<ReferenceSymbol>>): string | null {
  const tokens = extractIntentTokens(query);
  if (tokens.length === 0 || symbols.length === 0) return null;
  const selected = symbols
    .map((symbol) => ({ symbol, score: scoreSymbol(symbol, tokens) }))
    .filter((candidate) => candidate.score > 0)
    .sort((left, right) => right.score - left.score
      || stringField(left.symbol.simpleName).localeCompare(stringField(right.symbol.simpleName)))
    .slice(0, 12);
  if (selected.length === 0) return null;
  const lines = ['== Reference business evidence retrieved for the current plan =='];
  for (const { symbol, score } of selected) {
    const fields = recordEntries(symbol.fields).slice(0, 20).map(([name, type]) => `${name}:${type}`);
    lines.push([
      `- score=${score}`,
      `[${stringField(symbol.type) || 'UNKNOWN'}]`,
      stringField(symbol.simpleName) || '(unnamed)',
      `module=${stringField(symbol.module) || '(unknown)'}`,
      stringField(symbol.relativePath) ? `path=${stringField(symbol.relativePath)}` : '',
      fields.length > 0 ? `fields=${fields.join(',')}` : '',
    ].filter(Boolean).join(' | '));
  }
  return lines.join('\n');
}


export function formatReferenceReviewEvidenceForGeneration(evidence: ReferenceReviewEvidence): string | null {
  return formatReferenceReviewEvidence(evidence, { maxSymbols: 10, maxRelations: 12, maxAnomalies: 8 });
}


export function formatReferenceReviewEvidenceForSemanticReview(evidence: ReferenceReviewEvidence): string | null {
  return formatReferenceReviewEvidence(evidence, { maxSymbols: 8, maxRelations: 8, maxAnomalies: 8 });
}

export function buildCanonicalReferenceIntent(contract: PlanContract): string {
  const parts = [
    contract.identity.moduleName,
    contract.identity.entityName,
    contract.identity.tableName,
    ...contract.domains.map((item) => item.name),
    ...contract.modules.map((item) => item.name),
    ...contract.entities.flatMap((item) => [item.name, item.table ?? '']),
    ...contract.states.map((item) => item.name),
    ...contract.integrations.flatMap((item) => [item.sourceModule, item.targetModule, item.type, item.entrypoint]),
  ];
  const unique = Array.from(new Set(parts.map((item) => item?.trim()).filter((item): item is string => Boolean(item))));
  return normalizeSearchIntent(unique.join(' | ')).slice(0, 1_500);
}

function normalizeSearchIntent(value: string): string {
  return value.trim().replace(/\s+/g, ' ').slice(0, 20_000);
}

function extractIntentTokens(value: string): string[] {
  const tokens = new Set<string>();
  const expanded = value.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_/.-]+/g, ' ');
  for (const match of expanded.matchAll(/[A-Za-z][A-Za-z0-9]{2,}/g)) {
    const token = match[0].toLowerCase();
    if (!STOP_WORDS.has(token)) tokens.add(token);
  }
  const lower = value.toLowerCase();
  for (const [phrase, aliases] of DOMAIN_ALIASES) {
    if (!lower.includes(phrase)) continue;
    aliases.forEach((alias) => tokens.add(alias));
  }
  return Array.from(tokens).slice(0, 48);
}

function scoreSymbol(symbol: Partial<ReferenceSymbol>, tokens: string[]): number {
  const name = stringField(symbol.simpleName).toLowerCase();
  const moduleName = stringField(symbol.module).toLowerCase();
  const table = stringField(symbol.tableName).toLowerCase();
  const pkg = stringField(symbol.packageName).toLowerCase();
  const path = stringField(symbol.relativePath).toLowerCase();
  const fields = recordEntries(symbol.fields).map(([field, type]) => `${field} ${type}`.toLowerCase()).join(' ');
  const methods = stringArray(symbol.publicMethodSignatures).join(' ').toLowerCase();
  let score = 0;
  for (const token of tokens) {
    if (name === token) score += 40;
    else if (name.includes(token)) score += 18;
    if (table === token) score += 35;
    else if (table.includes(token)) score += 14;
    if (moduleName === token) score += 25;
    else if (moduleName.includes(token)) score += 10;
    if (fields.includes(token)) score += 8;
    if (methods.includes(token)) score += 6;
    if (pkg.includes(token)) score += 4;
    if (path.includes(token)) score += 3;
  }
  const type = stringField(symbol.type);
  if (score > 0 && (type === 'ENTITY' || type === 'SERVICE' || type === 'CONTROLLER' || type === 'FEIGN')) score += 2;
  return score;
}

const DOMAIN_ALIASES: ReadonlyArray<readonly [string, readonly string[]]> = [
  ['\u52a8\u706b', ['hotwork', 'workorder', 'task']],
  ['\u4f5c\u4e1a\u7968', ['workorder', 'workticket', 'task']],
  ['\u7279\u6b8a\u65f6\u6bb5', ['specialperiod', 'period', 'riskdates']],
  ['\u8282\u5047\u65e5', ['holiday', 'riskdates', 'date']],
  ['\u516c\u4f11\u65e5', ['holiday', 'riskdates', 'date']],
  ['\u591c\u95f4', ['night', 'period', 'time']],
  ['\u5ba1\u6279', ['flow', 'examint', 'node']],
  ['\u5de5\u4f5c\u6d41', ['flow', 'node']],
  ['\u5bfc\u5165', ['excel', 'import']],
  ['\u5bfc\u51fa', ['excel', 'export']],
];

const STOP_WORDS = new Set([
  'blade', 'bladex', 'springblade', 'java', 'swagger', 'nacos', 'namespace',
  'service', 'controller', 'entity', 'mapper', 'wrapper', 'module', 'package',
  'string', 'integer', 'boolean', 'true', 'false', 'null', 'plan', 'backend',
]);

function isReferenceDecision(value: unknown): value is ReferenceAccessDecisionType {
  return value === 'REUSE' || value === 'EXTEND' || value === 'NEW'
    || value === 'ARCHITECTURE_DECISION_REQUIRED';
}

function recordEntries(value: unknown): Array<[string, string]> {
  if (!isRecord(value)) return [];
  return Object.entries(value).flatMap(([key, entry]) =>
    typeof entry === 'string' ? [[key, entry] as [string, string]] : []);
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

function stringField(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every((entry) => typeof entry === 'string');
}

function nullableString(value: unknown): value is string | null | undefined {
  return value == null || typeof value === 'string';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
