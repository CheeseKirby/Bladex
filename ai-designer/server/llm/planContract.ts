import { createHash } from 'node:crypto';
import type {
  ReferenceAccessDecision,
  ReferenceReviewEvidence,
  ReferenceSymbol,
} from '../services/referenceSummary';

export const PLAN_CONTRACT_VERSION = '2.0';
export const PLAN_CONTRACT_RULESET_VERSION = 'canonical-plan-v2';
const CONTRACT_BLOCK = /```plan-contract\s*([\s\S]*?)```/i;
const CONTRACT_BLOCK_GLOBAL = /```plan-contract\s*([\s\S]*?)```/gi;

export interface PlanDomainContract {
  id: string;
  name: string;
  ownerModuleIds: string[];
}

export interface PlanAggregateContract {
  id: string;
  name: string;
  domainId: string;
  rootEntityId: string;
}

export interface PlanModuleContract {
  id: string;
  name: string;
  basePackage?: string;
  kind: 'EXISTING' | 'NEW' | 'UNKNOWN';
}

export interface PlanIdentityContract {
  moduleName: string;
  entityName: string;
  tableName: string;
  basePackage: string;
  apiModuleName: string;
  serviceModuleName: string;
  serviceName: string;
}

export interface PlanFieldContract {
  id: string;
  entityId: string;
  name: string;
  columnName: string;
  javaType: string;
  required: boolean;
  role: 'PERSISTENT' | 'DERIVED';
  evidence?: string;
}

export interface PlanEntityContract {
  id: string;
  name: string;
  moduleId?: string;
  aggregateId?: string;
  table?: string;
  fields: string[];
  fieldIds?: string[];
}

export interface PlanStateTransitionContract {
  from: string;
  to: string;
  trigger: string;
}

export interface PlanStateContract {
  id: string;
  name: string;
  ownerId?: string;
  values: string[];
  transitions?: PlanStateTransitionContract[];
  referenceField?: string;
}

export interface PlanIntegrationContract {
  id: string;
  type: 'API' | 'FEIGN' | 'WORKFLOW' | 'EVENT' | 'OTHER';
  sourceModule?: string;
  targetModule?: string;
  entrypoint?: string;
}

export interface PlanDeliverableContract {
  id: string;
  kind: 'DDL' | 'ENTITY' | 'VO' | 'MAPPER' | 'SERVICE' | 'WRAPPER' | 'CONTROLLER' | 'FEIGN' | 'EXCEL' | 'CONFIG' | 'OTHER';
  name: string;
  moduleId?: string;
  className?: string;
  moduleSide?: 'API' | 'IMPL' | 'DOC' | 'UNKNOWN';
  action?: 'CREATE' | 'MODIFY' | 'EXTEND' | 'PROHIBIT';
  providesTypes?: string[];
  requiresTypes?: string[];
}

export interface PlanReferenceBinding {
  id: string;
  planElementId: string;
  referenceSymbol: string;
  decision: 'REUSE' | 'EXTEND';
  targetModule?: string;
}

export interface PlanArchitectureDecision {
  id: string;
  decision: string;
  rationale: string;
  evidence: string[];
}

export interface PlanContract {
  contractVersion: typeof PLAN_CONTRACT_VERSION;
  sourceHash: string;
  sourceMode: 'STRUCTURED' | 'LEGACY_INFERRED';
  referenceSnapshotId?: string;
  rulesetVersion: string;
  identity: PlanIdentityContract;
  fields: PlanFieldContract[];
  domains: PlanDomainContract[];
  modules: PlanModuleContract[];
  aggregates: PlanAggregateContract[];
  entities: PlanEntityContract[];
  states: PlanStateContract[];
  integrations: PlanIntegrationContract[];
  deliverables: PlanDeliverableContract[];
  referenceBindings: PlanReferenceBinding[];
  architectureDecisions: PlanArchitectureDecision[];
}

export interface PlanContractCompilation {
  contract: PlanContract;
  source: 'EMBEDDED' | 'INFERRED';
  diagnostics: string[];
}

export interface DeterministicPlanIssue {
  severity: 'ERROR' | 'WARN';
  rule: string;
  message: string;
}

export type PlanRepairOperationName =
  | 'ADD_MODULE'
  | 'MOVE_ENTITY'
  | 'SPLIT_AGGREGATE'
  | 'BIND_EXISTING_SYMBOL'
  | 'ADD_REFERENCE_BINDING'
  | 'ADD_DELIVERABLE'
  | 'ADD_INTEGRATION'
  | 'CHANGE_STATE_OWNER'
  | 'DECLARE_ARCHITECTURE_DECISION'
  | 'NORMALIZE_DELIVERABLE_TOPOLOGY'
  | 'RENAME_ENTITY';

export interface PlanRepairOperation {
  operation: PlanRepairOperationName;
  targetId?: string;
  arguments: Record<string, unknown>;
  resolves: string[];
  preconditions: string[];
}

export interface PlanRepairResult {
  contract: PlanContract;
  applied: PlanRepairOperation[];
  rejected: Array<{ repair: PlanRepairOperation; reason: string }>;
}

export function compilePlanContract(planContent: string): PlanContractCompilation {
  const sourceWithoutContract = stripPlanContractBlock(planContent);
  const sourceHash = hashText(sourceWithoutContract);
  const contractBlocks = Array.from(planContent.matchAll(CONTRACT_BLOCK_GLOBAL));
  if (contractBlocks.length > 1) {
    return {
      contract: inferPlanContract(sourceWithoutContract, sourceHash),
      source: 'INFERRED',
      diagnostics: ['Multiple plan-contract blocks are forbidden; the content was treated as legacy Markdown.'],
    };
  }
  const match = planContent.match(CONTRACT_BLOCK);
  if (match) {
    try {
      const parsed = JSON.parse(match[1].trim()) as unknown;
      const contract = parsePlanContract(parsed, sourceHash);
      if (contract) return { contract, source: 'EMBEDDED', diagnostics: [] };
      return {
        contract: inferPlanContract(sourceWithoutContract, sourceHash),
        source: 'INFERRED',
        diagnostics: ['Embedded plan-contract block failed schema validation and was replaced by an inferred contract.'],
      };
    } catch (error) {
      return {
        contract: inferPlanContract(sourceWithoutContract, sourceHash),
        source: 'INFERRED',
        diagnostics: [`Embedded plan-contract JSON could not be parsed: ${errorMessage(error)}`],
      };
    }
  }
  return {
    contract: inferPlanContract(sourceWithoutContract, sourceHash),
    source: 'INFERRED',
    diagnostics: ['Plan did not contain a plan-contract block; a deterministic compatibility contract was inferred.'],
  };
}

export function upsertPlanContractBlock(planContent: string, contract: PlanContract): string {
  const normalized = normalizeContract(contract, hashText(stripPlanContractBlock(planContent)));
  const block = `\n\n## Machine-readable plan contract\n\n\`\`\`plan-contract\n${JSON.stringify(normalized, null, 2)}\n\`\`\`\n`;
  return stripPlanContractBlock(planContent).trimEnd() + block;
}

export function stripPlanContractBlock(planContent: string): string {
  return planContent
    .replace(/\n*## Machine-readable plan contract\s*/gi, '\n')
    .replace(CONTRACT_BLOCK_GLOBAL, '')
    .trimEnd();
}

export function validatePlanContract(
  compilation: PlanContractCompilation,
  reference: ReferenceReviewEvidence,
  sourceText: string,
): DeterministicPlanIssue[] {
  const { contract } = compilation;
  const issues: DeterministicPlanIssue[] = [];
  const add = (severity: 'ERROR' | 'WARN', rule: string, message: string) => {
    if (!issues.some((issue) => issue.rule === rule && issue.message === message)) {
      issues.push({ severity, rule, message });
    }
  };

  if (compilation.source === 'INFERRED') {
    add('WARN', 'PLAN-CONTRACT-INFERRED', compilation.diagnostics[0]
      ?? 'The plan contract was inferred from legacy Markdown.');
  }
  for (const diagnostic of compilation.diagnostics.slice(1)) {
    add('WARN', 'PLAN-CONTRACT-DIAGNOSTIC', diagnostic);
  }

  const ids = collectContractIds(contract);
  const duplicateIds = ids.filter((id, index) => ids.indexOf(id) !== index);
  for (const id of new Set(duplicateIds)) {
    add('ERROR', 'PLAN-CONTRACT-DUPLICATE-ID', `Contract id is not unique: ${id}`);
  }

  const moduleIds = new Set(contract.modules.map((module) => module.id));
  const domainIds = new Set(contract.domains.map((domain) => domain.id));
  const aggregateIds = new Set(contract.aggregates.map((aggregate) => aggregate.id));
  const entityIds = new Set(contract.entities.map((entity) => entity.id));
  for (const domain of contract.domains) {
    for (const ownerModuleId of domain.ownerModuleIds) {
      if (!moduleIds.has(ownerModuleId)) {
        add('ERROR', 'PLAN-CONTRACT-UNRESOLVED-REFERENCE',
          `Domain ${domain.name} references missing owner module ${ownerModuleId}.`);
      }
    }
  }
  for (const aggregate of contract.aggregates) {
    if (!domainIds.has(aggregate.domainId)) {
      add('ERROR', 'PLAN-CONTRACT-UNRESOLVED-REFERENCE',
        `Aggregate ${aggregate.name} references missing domain ${aggregate.domainId}.`);
    }
    if (!entityIds.has(aggregate.rootEntityId)) {
      add('ERROR', 'PLAN-CONTRACT-UNRESOLVED-REFERENCE',
        `Aggregate ${aggregate.name} references missing root entity ${aggregate.rootEntityId}.`);
    }
  }
  for (const entity of contract.entities) {
    if (entity.moduleId && !moduleIds.has(entity.moduleId)) {
      add('ERROR', 'PLAN-CONTRACT-UNRESOLVED-REFERENCE',
        `Entity ${entity.name} references missing module id ${entity.moduleId}.`);
    }
    if (entity.aggregateId && !aggregateIds.has(entity.aggregateId)) {
      add('ERROR', 'PLAN-CONTRACT-UNRESOLVED-REFERENCE',
        `Entity ${entity.name} references missing aggregate id ${entity.aggregateId}.`);
    }
  }
  if (contract.entities.length !== 1) {
    add('ERROR', 'PLAN-MULTI-ENTITY-UNSUPPORTED',
      `Canonical generation identity supports exactly one generated entity, found ${contract.entities.length}.`);
  }
  const identityEntity = contract.entities.find((entity) => entity.name === contract.identity.entityName);
  if (!identityEntity) {
    add('ERROR', 'PLAN-IDENTITY-ENTITY-MISMATCH',
      `No contract entity matches canonical identity ${contract.identity.entityName}.`);
  } else {
    if (identityEntity.table && identityEntity.table !== contract.identity.tableName) {
      add('ERROR', 'PLAN-IDENTITY-TABLE-MISMATCH',
        `Entity table ${identityEntity.table} differs from canonical table ${contract.identity.tableName}.`);
    }
    const ownerModule = contract.modules.find((module) => module.id === identityEntity.moduleId);
    if (ownerModule && ownerModule.name !== contract.identity.moduleName) {
      add('ERROR', 'PLAN-IDENTITY-MODULE-MISMATCH',
        `Entity owner module ${ownerModule.name} differs from canonical module ${contract.identity.moduleName}.`);
    }
  }
  for (const binding of contract.referenceBindings) {
    if (!ids.includes(binding.planElementId)) {
      add('ERROR', 'PLAN-CONTRACT-UNRESOLVED-REFERENCE',
        `Reference binding ${binding.id} targets missing plan element ${binding.planElementId}.`);
    }
  }

  const activeDeliverables = contract.deliverables.filter((deliverable) => deliverable.action !== 'PROHIBIT');
  if (contract.entities.length > 0) {
    const kinds = new Set(activeDeliverables.map((deliverable) => deliverable.kind));
    for (const required of ['DDL', 'ENTITY', 'VO', 'MAPPER', 'SERVICE', 'CONTROLLER'] as const) {
      if (!kinds.has(required)) {
        add('ERROR', 'DELIVERABLE-MISSING', `Entity-backed plan is missing required ${required} deliverable.`);
      }
    }
  }

  const providedBy = new Map<string, string[]>();
  const referencedTypes = new Set(contract.referenceBindings.map((binding) => {
    const symbol = binding.referenceSymbol;
    return symbol.slice(symbol.lastIndexOf('.') + 1);
  }));
  const frameworkTypes = new Set(['String', 'Long', 'Integer', 'Boolean', 'Date', 'LocalDateTime', 'BigDecimal',
    'R', 'IPage', 'Query', 'HttpServletResponse', 'MultipartFile']);
  for (const deliverable of activeDeliverables) {
    const provided = deliverable.providesTypes ?? [];
    const required = deliverable.requiresTypes ?? [];
    if (new Set(provided).size !== provided.length || new Set(required).size !== required.length) {
      add('ERROR', 'DELIVERABLE-TYPE-DUPLICATE', `Deliverable ${deliverable.id} contains duplicate type symbols.`);
    }
    if (deliverable.className && ['ENTITY', 'VO', 'MAPPER', 'SERVICE', 'WRAPPER', 'CONTROLLER', 'FEIGN', 'EXCEL', 'OTHER'].includes(deliverable.kind)
      && !provided.includes(deliverable.className)) {
      add('ERROR', 'DELIVERABLE-CLASS-NOT-PROVIDED',
        `Deliverable ${deliverable.id} className ${deliverable.className} is absent from providesTypes.`);
    }
    const invalidShape = deliverable.kind === 'ENTITY' ? provided.length !== 1
      : deliverable.kind === 'VO' ? provided.length === 0 || provided.some((type) => !type.endsWith('VO'))
        : deliverable.kind === 'MAPPER' ? provided.length !== 1 || !provided[0]?.endsWith('Mapper')
          : deliverable.kind === 'SERVICE' ? !provided.some((type) => type.startsWith('I') && type.endsWith('Service'))
            || !provided.some((type) => type.endsWith('ServiceImpl'))
            : deliverable.kind === 'WRAPPER' ? provided.length !== 1 || !provided[0]?.endsWith('Wrapper')
            : deliverable.kind === 'CONTROLLER' ? provided.length !== 1 || !provided[0]?.endsWith('Controller')
              : ['FEIGN', 'EXCEL'].includes(deliverable.kind) ? provided.length === 0
                : deliverable.kind === 'OTHER' && deliverable.className ? provided.length !== 1 : false;
    if (invalidShape) {
      add('ERROR', 'DELIVERABLE-TYPE-SHAPE-INVALID',
        `Deliverable ${deliverable.id} providesTypes do not match kind ${deliverable.kind}.`);
    }
    for (const type of provided) {
      const owners = providedBy.get(type) ?? [];
      owners.push(deliverable.id);
      providedBy.set(type, owners);
    }
  }
  for (const [type, owners] of providedBy) {
    if (owners.length > 1) {
      add('ERROR', 'TYPE-PROVIDER-DUPLICATE', `Business type ${type} has multiple providers: ${owners.join(', ')}.`);
    }
  }
  for (const deliverable of activeDeliverables) {
    for (const type of deliverable.requiresTypes ?? []) {
      if (!providedBy.has(type) && !referencedTypes.has(type) && !frameworkTypes.has(type)) {
        add('ERROR', 'TYPE-PROVIDER-MISSING', `Deliverable ${deliverable.id} requires business type ${type} with no provider.`);
      }
    }
  }

  for (const state of contract.states) {
    if (!state.ownerId) {
      add('ERROR', 'STATE-OWNERSHIP-UNDEFINED', `State model ${state.name} has no owning aggregate/entity.`);
    } else if (!ids.includes(state.ownerId)) {
      add('ERROR', 'PLAN-CONTRACT-UNRESOLVED-REFERENCE',
        `State model ${state.name} references missing owner ${state.ownerId}.`);
    }
  }

  const search = reference.search;
  if (!search) {
    add('WARN', 'REFERENCE-EVIDENCE-UNAVAILABLE',
      'Intent-scoped reference evidence was unavailable; ownership and reuse checks are incomplete.');
    return issues;
  }

  const sourceLower = sourceText.toLowerCase();
  for (const anomaly of search.anomalies) {
    if (anomaly.code === 'REF-DANGLING-MODULE' && danglingAnomalyIsRelevant(anomaly.message, sourceLower, contract)) {
      const acknowledged = contract.architectureDecisions.length > 0;
      add(acknowledged ? 'WARN' : 'ERROR', anomaly.code,
        `${anomaly.message} Evidence: ${anomaly.evidencePath}${acknowledged ? ' (acknowledged by architecture decision)' : ''}`);
    }
    if (anomaly.code === 'REF-DUPLICATE-CAPABILITY') {
      add(anomaly.severity, anomaly.code, `${anomaly.message} Evidence: ${anomaly.evidencePath}`);
    }
  }

  for (const decision of search.decisions) {
    validateReferenceDecision(contract, decision, search.symbols, add);
  }

  if (hasMixedAggregateName(contract.entities, search.symbols)) {
    add('ERROR', 'DOMAIN-AGGREGATE-MIXED',
      'A planned entity combines special-period/risk-date and work-order/hot-work concepts that are separate reference capabilities. Split ownership or document an explicit architecture decision.');
  }

  const referenceStateFields = new Set(search.symbols.flatMap((symbol) => Object.keys(symbol.fields)));
  if ((referenceStateFields.has('state') || referenceStateFields.has('workState'))
    && contract.states.some((state) => !state.ownerId)) {
    add('ERROR', 'STATE-SEMANTIC-CONFLICT',
      'Reference entities already expose state/workState semantics, but the plan introduces an ownerless state model. Bind state ownership and mapping explicitly.');
  }

  return issues;
}

export function planSafeDeterministicRepairs(
  contract: PlanContract,
  issues: DeterministicPlanIssue[],
  reference: ReferenceReviewEvidence,
): PlanRepairOperation[] {
  const rules = new Set(issues.filter((issue) => issue.severity === 'ERROR').map((issue) => issue.rule));
  const repairs: PlanRepairOperation[] = [];

  if (rules.has('STATE-OWNERSHIP-UNDEFINED') && contract.states.length > 0) {
    const ownerId = contract.aggregates.length === 1
      ? contract.aggregates[0].id
      : contract.entities.length === 1 ? contract.entities[0].id : undefined;
    if (ownerId) {
      for (const state of contract.states.filter((candidate) => !candidate.ownerId)) {
        repairs.push({
          operation: 'CHANGE_STATE_OWNER',
          targetId: state.id,
          arguments: { ownerId },
          resolves: ['STATE-OWNERSHIP-UNDEFINED', 'STATE-SEMANTIC-CONFLICT'],
          preconditions: [`${state.id} exists`, `${ownerId} is the only unambiguous aggregate/entity owner`],
        });
      }
    }
  }

  const topologyRules = [
    'DELIVERABLE-TYPE-DUPLICATE',
    'DELIVERABLE-TYPE-SHAPE-INVALID',
    'DELIVERABLE-CLASS-NOT-PROVIDED',
    'TYPE-PROVIDER-DUPLICATE',
    'TYPE-PROVIDER-MISSING',
  ];
  if (contract.sourceMode === 'STRUCTURED' && contract.entities.length === 1
    && topologyRules.some((rule) => rules.has(rule))) {
    repairs.push({
      operation: 'NORMALIZE_DELIVERABLE_TOPOLOGY',
      arguments: {},
      resolves: topologyRules.filter((rule) => rules.has(rule)),
      preconditions: [
        'The contract is STRUCTURED',
        `${contract.entities[0].id} is the only planned entity`,
        'Standard BladeX artifact ownership is mechanically derivable from the canonical identity',
      ],
    });
  }

  if (rules.has('DELIVERABLE-MISSING')) {
    const existingKinds = new Set(contract.deliverables.map((deliverable) => deliverable.kind));
    const moduleId = contract.modules.length === 1 ? contract.modules[0].id : undefined;
    for (const kind of ['DDL', 'ENTITY', 'VO', 'MAPPER', 'SERVICE', 'CONTROLLER'] as const) {
      if (existingKinds.has(kind)) continue;
      repairs.push({
        operation: 'ADD_DELIVERABLE',
        arguments: { kind, name: kind, ...(moduleId ? { moduleId } : {}) },
        resolves: ['DELIVERABLE-MISSING'],
        preconditions: ['The contract contains an entity-backed implementation plan'],
      });
    }
  }

  const decision = reference.search?.decisions[0];
  if (rules.has('REF-DUPLICATE-CAPABILITY') && decision
    && (decision.decision === 'REUSE' || decision.decision === 'EXTEND')
    && contract.referenceBindings.length === 0 && contract.entities.length === 1
    && decision.evidenceSymbols.length > 0) {
    repairs.push({
      operation: 'ADD_REFERENCE_BINDING',
      targetId: contract.entities[0].id,
      arguments: {
        referenceSymbol: decision.evidenceSymbols[0],
        decision: decision.decision,
        ...(decision.targetModule ? { targetModule: decision.targetModule } : {}),
      },
      resolves: ['REF-DUPLICATE-CAPABILITY'],
      preconditions: [
        `${contract.entities[0].id} is the only planned entity`,
        `${decision.evidenceSymbols[0]} is present in reference snapshot ${reference.search?.snapshotId}`,
      ],
    });
  }

  return repairs;
}

export function applyPlanRepairOperations(
  contract: PlanContract,
  repairs: PlanRepairOperation[],
): PlanRepairResult {
  const next = structuredClone(contract);
  const applied: PlanRepairOperation[] = [];
  const rejected: Array<{ repair: PlanRepairOperation; reason: string }> = [];

  for (const repair of repairs) {
    try {
      applyOneRepair(next, repair);
      applied.push(repair);
    } catch (error) {
      rejected.push({ repair, reason: errorMessage(error) });
    }
  }
  return { contract: normalizeContract(next, next.sourceHash), applied, rejected };
}

function applyOneRepair(contract: PlanContract, repair: PlanRepairOperation): void {
  switch (repair.operation) {
    case 'ADD_MODULE': {
      const name = requiredArgument(repair, 'name');
      const normalizedName = normalizeModuleName(name);
      const id = optionalArgument(repair, 'id') ?? `module.${toId(normalizedName)}`;
      if (!contract.modules.some((module) => module.id === id || normalizeModuleName(module.name) === normalizedName)) {
        const kind = enumArgument(repair, 'kind', ['EXISTING', 'NEW', 'UNKNOWN'] as const, 'UNKNOWN');
        const basePackage = optionalArgument(repair, 'basePackage');
        contract.modules.push({ id, name: normalizedName, kind, ...(basePackage ? { basePackage } : {}) });
      }
      ensureDomainForModule(contract, id, normalizedName);
      return;
    }
    case 'MOVE_ENTITY': {
      const entity = requireEntity(contract, repair.targetId);
      const moduleName = normalizeModuleName(requiredArgument(repair, 'moduleName'));
      let module = contract.modules.find((candidate) => normalizeModuleName(candidate.name) === moduleName);
      if (!module) {
        module = {
          id: `module.${toId(moduleName)}`,
          name: moduleName,
          kind: enumArgument(repair, 'kind', ['EXISTING', 'NEW', 'UNKNOWN'] as const, 'EXISTING'),
          basePackage: optionalArgument(repair, 'basePackage') ?? `org.springblade.${moduleName.replace(/-/g, '')}`,
        };
        contract.modules.push(module);
      }
      entity.moduleId = module.id;
      const domain = ensureDomainForModule(contract, module.id, module.name);
      const aggregate = contract.aggregates.find((candidate) => candidate.id === entity.aggregateId);
      if (aggregate) aggregate.domainId = domain.id;
      return;
    }
    case 'RENAME_ENTITY': {
      const entity = requireEntity(contract, repair.targetId);
      const oldName = entity.name;
      const newName = requiredArgument(repair, 'name');
      entity.name = newName;
      if (contract.identity.entityName === oldName) contract.identity.entityName = newName;
      const aggregate = contract.aggregates.find((candidate) => candidate.id === entity.aggregateId);
      if (aggregate) aggregate.name = `${newName}Aggregate`;
      const renameType = (type: string): string => {
        if (type === oldName) return newName;
        if (type.startsWith(`I${oldName}`)) return `I${newName}${type.slice(oldName.length + 1)}`;
        if (type.startsWith(oldName)) return `${newName}${type.slice(oldName.length)}`;
        return type;
      };
      contract.deliverables = contract.deliverables.map((deliverable) => ({
        ...deliverable,
        name: renameType(deliverable.name),
        ...(deliverable.className ? { className: renameType(deliverable.className) } : {}),
        ...(deliverable.providesTypes ? { providesTypes: deliverable.providesTypes.map(renameType) } : {}),
        ...(deliverable.requiresTypes ? { requiresTypes: deliverable.requiresTypes.map(renameType) } : {}),
      }));
      return;
    }
    case 'SPLIT_AGGREGATE': {
      const entity = requireEntity(contract, repair.targetId);
      const rawEntities = repair.arguments.entities;
      if (!Array.isArray(rawEntities) || rawEntities.length < 2) {
        throw new Error('SPLIT_AGGREGATE requires arguments.entities with at least two entities');
      }
      const parsed = rawEntities.map((raw, index) => parseRepairEntity(raw, entity, index));
      const originalAggregate = contract.aggregates.find((aggregate) => aggregate.id === entity.aggregateId);
      contract.aggregates = contract.aggregates.filter((aggregate) => aggregate.id !== entity.aggregateId);
      for (const parsedEntity of parsed) {
        const aggregateId = `aggregate.${toId(parsedEntity.name)}`;
        parsedEntity.aggregateId = aggregateId;
        contract.aggregates.push({
          id: aggregateId,
          name: `${parsedEntity.name}Aggregate`,
          domainId: originalAggregate?.domainId ?? contract.domains[0]?.id ?? 'domain.main',
          rootEntityId: parsedEntity.id,
        });
      }
      contract.entities = contract.entities.filter((candidate) => candidate.id !== entity.id).concat(parsed);
      for (const state of contract.states) {
        if (state.ownerId === entity.id) state.ownerId = parsed[0].id;
      }
      for (const binding of contract.referenceBindings) {
        if (binding.planElementId === entity.id) binding.planElementId = parsed[0].id;
      }
      return;
    }
    case 'BIND_EXISTING_SYMBOL':
    case 'ADD_REFERENCE_BINDING': {
      const planElementId = repair.targetId ?? requiredArgument(repair, 'planElementId');
      if (!collectContractIds(contract).includes(planElementId)) throw new Error(`Unknown plan element: ${planElementId}`);
      const referenceSymbol = requiredArgument(repair, 'referenceSymbol');
      const decision = enumArgument(repair, 'decision', ['REUSE', 'EXTEND'] as const);
      const targetModule = optionalArgument(repair, 'targetModule');
      const id = optionalArgument(repair, 'id') ?? `binding.${toId(referenceSymbol)}`;
      const existing = contract.referenceBindings.find((binding) => binding.id === id
        || (binding.planElementId === planElementId && binding.referenceSymbol === referenceSymbol));
      if (existing) {
        existing.decision = decision;
        existing.targetModule = targetModule;
      } else {
        contract.referenceBindings.push({ id, planElementId, referenceSymbol, decision, ...(targetModule ? { targetModule } : {}) });
      }
      return;
    }
    case 'ADD_DELIVERABLE': {
      const kind = enumArgument(repair, 'kind',
        ['DDL', 'ENTITY', 'VO', 'MAPPER', 'SERVICE', 'WRAPPER', 'CONTROLLER', 'FEIGN', 'EXCEL', 'CONFIG', 'OTHER'] as const);
      const name = optionalArgument(repair, 'name') ?? kind;
      const moduleId = optionalArgument(repair, 'moduleId');
      if (moduleId && !contract.modules.some((module) => module.id === moduleId)) {
        throw new Error(`Unknown module id: ${moduleId}`);
      }
      const id = optionalArgument(repair, 'id') ?? `deliverable.${kind.toLowerCase()}`;
      if (!contract.deliverables.some((deliverable) => deliverable.id === id || deliverable.kind === kind)) {
        contract.deliverables.push({ id, kind, name, ...(moduleId ? { moduleId } : {}) });
      }
      return;
    }
    case 'NORMALIZE_DELIVERABLE_TOPOLOGY': {
      normalizeStructuredDeliverableTopology(contract);
      return;
    }
    case 'ADD_INTEGRATION': {
      const type = enumArgument(repair, 'type', ['API', 'FEIGN', 'WORKFLOW', 'EVENT', 'OTHER'] as const);
      const entrypoint = requiredArgument(repair, 'entrypoint');
      const sourceModule = optionalArgument(repair, 'sourceModule');
      const targetModule = optionalArgument(repair, 'targetModule');
      const id = optionalArgument(repair, 'id') ?? `integration.${toId(`${type}-${targetModule ?? entrypoint}`)}`;
      const existing = contract.integrations.find((integration) => integration.id === id);
      const value: PlanIntegrationContract = {
        id, type, entrypoint,
        ...(sourceModule ? { sourceModule } : {}),
        ...(targetModule ? { targetModule } : {}),
      };
      if (existing) Object.assign(existing, value);
      else contract.integrations.push(value);
      return;
    }
    case 'CHANGE_STATE_OWNER': {
      const state = contract.states.find((candidate) => candidate.id === repair.targetId);
      if (!state) throw new Error(`Unknown state id: ${repair.targetId ?? '(missing)'}`);
      const ownerId = requiredArgument(repair, 'ownerId');
      const ownerExists = contract.entities.some((entity) => entity.id === ownerId)
        || contract.aggregates.some((aggregate) => aggregate.id === ownerId);
      if (!ownerExists) throw new Error(`Unknown state owner: ${ownerId}`);
      state.ownerId = ownerId;
      return;
    }
    case 'DECLARE_ARCHITECTURE_DECISION': {
      const decision = requiredArgument(repair, 'decision');
      const rationale = requiredArgument(repair, 'rationale');
      const evidence = stringArrayArgument(repair, 'evidence');
      const id = optionalArgument(repair, 'id') ?? `adr.${contract.architectureDecisions.length + 1}`;
      const existing = contract.architectureDecisions.find((item) => item.id === id);
      const value = { id, decision, rationale, evidence };
      if (existing) Object.assign(existing, value);
      else contract.architectureDecisions.push(value);
      return;
    }
    default: {
      const neverOperation: never = repair.operation;
      throw new Error(`Unsupported repair operation: ${neverOperation}`);
    }
  }
}


function normalizeStructuredDeliverableTopology(contract: PlanContract): void {
  if (contract.sourceMode !== 'STRUCTURED' || contract.entities.length !== 1) {
    throw new Error('NORMALIZE_DELIVERABLE_TOPOLOGY requires one STRUCTURED contract entity');
  }
  const entityName = contract.identity.entityName;
  const standardVoTypes = [`${entityName}VO`, `${entityName}QVO`, `${entityName}IVO`, `${entityName}UVO`, `${entityName}EVO`];
  const active = contract.deliverables.filter((item) => item.action !== 'PROHIBIT');
  const activeVos = active.filter((item) => item.kind === 'VO');
  const activeServices = active.filter((item) => item.kind === 'SERVICE');
  if (activeVos.length === 0 || activeServices.length === 0) {
    throw new Error('NORMALIZE_DELIVERABLE_TOPOLOGY requires active VO and SERVICE deliverables');
  }
  const excelOwnsEvo = active.some((item) => item.kind === 'EXCEL'
    && (item.className === `${entityName}EVO` || item.providesTypes?.includes(`${entityName}EVO`)));
  const canonicalVoTypes = standardVoTypes.filter((type) => !excelOwnsEvo || type !== `${entityName}EVO`);
  const canonicalVo = activeVos.find((item) => standardVoTypes.includes(item.className ?? '')) ?? activeVos[0];
  const canonicalService = activeServices.find((item) => item.className === `I${entityName}Service`) ?? activeServices[0];
  const next: PlanDeliverableContract[] = [];
  const seenKeys = new Set<string>();
  let entityInserted = false;
  let voInserted = false;
  let mapperInserted = false;
  let serviceInserted = false;
  let wrapperInserted = false;

  const push = (item: PlanDeliverableContract) => {
    const key = `${item.kind}|${item.className ?? ''}|${item.action ?? 'CREATE'}`;
    if (seenKeys.has(key)) return;
    seenKeys.add(key);
    next.push(item);
  };

  for (const deliverable of contract.deliverables) {
    if (deliverable.action === 'PROHIBIT') {
      push({ ...deliverable, providesTypes: [], requiresTypes: [] });
      continue;
    }
    switch (deliverable.kind) {
      case 'ENTITY':
        if (!entityInserted) {
          push({ ...deliverable, name: entityName, className: entityName, moduleSide: 'API',
            providesTypes: [entityName], requiresTypes: [] });
          entityInserted = true;
        }
        break;
      case 'VO':
        if (!voInserted) {
          push({ ...canonicalVo, name: `${entityName}VO`, className: `${entityName}VO`, moduleSide: 'API',
            providesTypes: canonicalVoTypes, requiresTypes: [entityName] });
          for (const custom of activeVos) {
            const customName = custom.className?.trim();
            if (!customName || standardVoTypes.includes(customName)) continue;
            if (!customName.endsWith('VO')) {
              throw new Error(`VO class ${customName} must end with VO`);
            }
            push({ ...custom, name: customName, className: customName, moduleSide: 'API',
              providesTypes: [customName], requiresTypes: [entityName] });
          }
          voInserted = true;
        }
        break;
      case 'MAPPER':
        if (!mapperInserted) {
          push({ ...deliverable, name: `${entityName}Mapper`, className: `${entityName}Mapper`, moduleSide: 'IMPL',
            providesTypes: [`${entityName}Mapper`], requiresTypes: [entityName, `${entityName}VO`, `${entityName}QVO`] });
          mapperInserted = true;
        }
        break;
      case 'SERVICE':
        if (!serviceInserted) {
          push({ ...canonicalService, name: `I${entityName}Service`, className: `I${entityName}Service`, moduleSide: 'IMPL',
            providesTypes: [`I${entityName}Service`, `${entityName}ServiceImpl`],
            requiresTypes: [entityName, `${entityName}VO`, `${entityName}QVO`, `${entityName}IVO`, `${entityName}UVO`] });
          serviceInserted = true;
        }
        break;
      case 'WRAPPER':
        if (!wrapperInserted) {
          push({ ...deliverable, name: `${entityName}Wrapper`, className: `${entityName}Wrapper`, moduleSide: 'IMPL',
            providesTypes: [`${entityName}Wrapper`], requiresTypes: [entityName, `${entityName}VO`] });
          wrapperInserted = true;
        }
        break;
      case 'CONTROLLER': {
        if (!wrapperInserted) {
          push({ id: 'deliverable.wrapper.1', kind: 'WRAPPER', name: `${entityName}Wrapper`,
            moduleId: deliverable.moduleId, className: `${entityName}Wrapper`, moduleSide: 'IMPL',
            action: deliverable.action ?? 'CREATE', providesTypes: [`${entityName}Wrapper`],
            requiresTypes: [entityName, `${entityName}VO`] });
          wrapperInserted = true;
        }
        const className = deliverable.className?.endsWith('Controller')
          ? deliverable.className : `${entityName}Controller`;
        push({ ...deliverable, name: className, className, moduleSide: 'IMPL', providesTypes: [className],
          requiresTypes: [`I${entityName}Service`, `${entityName}Wrapper`, `${entityName}VO`, `${entityName}QVO`, `${entityName}IVO`, `${entityName}UVO`] });
        break;
      }
      case 'FEIGN': {
        const className = deliverable.className || `I${entityName}Client`;
        push({ ...deliverable, name: className, className, moduleSide: 'API', providesTypes: [className],
          requiresTypes: [`${entityName}VO`] });
        break;
      }
      case 'EXCEL': {
        const className = deliverable.className || `${entityName}Excel`;
        push({ ...deliverable, name: className, className, providesTypes: [className], requiresTypes: [entityName] });
        break;
      }
      case 'DDL':
      case 'CONFIG':
        push({ ...deliverable, providesTypes: [], requiresTypes: [] });
        break;
      default:
        push({ ...deliverable,
          providesTypes: deliverable.className ? [deliverable.className] : deliverable.providesTypes ?? [],
          requiresTypes: deliverable.requiresTypes ?? [] });
    }
  }

  const provider = new Map<string, string>();
  for (const deliverable of next) {
    if (deliverable.action === 'PROHIBIT') continue;
    for (const type of deliverable.providesTypes ?? []) {
      const existing = provider.get(type);
      if (existing && existing !== deliverable.id) {
        throw new Error(`Type ${type} remains multiply provided by ${existing} and ${deliverable.id}`);
      }
      provider.set(type, deliverable.id);
    }
  }
  contract.deliverables = next;
}

function ensureDomainForModule(
  contract: PlanContract,
  moduleId: string,
  moduleName: string,
): PlanDomainContract {
  let domain = contract.domains.find((candidate) => candidate.ownerModuleIds.includes(moduleId)
    || candidate.id === `domain.${toId(moduleName)}`);
  if (!domain) {
    domain = { id: `domain.${toId(moduleName)}`, name: moduleName, ownerModuleIds: [moduleId] };
    contract.domains.push(domain);
  } else if (!domain.ownerModuleIds.includes(moduleId)) {
    domain.ownerModuleIds.push(moduleId);
  }
  return domain;
}

function parseRepairEntity(raw: unknown, original: PlanEntityContract, index: number): PlanEntityContract {
  if (!isRecord(raw) || !nonBlankString(raw.name)) throw new Error(`Invalid split entity at index ${index}`);
  const fields = isStringArray(raw.fields) ? raw.fields : [];
  const id = typeof raw.id === 'string' && raw.id.trim() ? raw.id : `entity.${toId(raw.name)}`;
  const moduleId = typeof raw.moduleId === 'string' ? raw.moduleId : original.moduleId;
  const table = typeof raw.table === 'string' ? raw.table : undefined;
  return { id, name: raw.name, fields, ...(moduleId ? { moduleId } : {}), ...(table ? { table } : {}) };
}

function requireEntity(contract: PlanContract, targetId: string | undefined): PlanEntityContract {
  const entity = contract.entities.find((candidate) => candidate.id === targetId);
  if (!entity) throw new Error(`Unknown entity id: ${targetId ?? '(missing)'}`);
  return entity;
}

function requiredArgument(repair: PlanRepairOperation, name: string): string {
  const value = repair.arguments[name];
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${repair.operation} requires string argument ${name}`);
  return value.trim();
}

function optionalArgument(repair: PlanRepairOperation, name: string): string | undefined {
  const value = repair.arguments[name];
  if (value == null) return undefined;
  if (typeof value !== 'string') throw new Error(`${repair.operation} argument ${name} must be a string`);
  return value.trim() || undefined;
}

function enumArgument<T extends readonly string[]>(
  repair: PlanRepairOperation,
  name: string,
  allowed: T,
  fallback?: T[number],
): T[number] {
  const value = repair.arguments[name];
  if (value == null && fallback) return fallback;
  if (typeof value !== 'string' || !allowed.includes(value)) {
    throw new Error(`${repair.operation} argument ${name} must be one of ${allowed.join(', ')}`);
  }
  return value as T[number];
}

function stringArrayArgument(repair: PlanRepairOperation, name: string): string[] {
  const value = repair.arguments[name];
  if (value == null) return [];
  if (!isStringArray(value)) throw new Error(`${repair.operation} argument ${name} must be a string array`);
  return value;
}

export function formatDeterministicIssues(issues: DeterministicPlanIssue[]): string {
  if (issues.length === 0) return 'Deterministic validation: PASS';
  return [
    '== Deterministic validation findings (cannot be overridden by the model) ==',
    ...issues.map((issue) => `- ${issue.severity} ${issue.rule}: ${issue.message}`),
  ].join('\n');
}

function validateReferenceDecision(
  contract: PlanContract,
  decision: ReferenceAccessDecision,
  symbols: ReferenceSymbol[],
  add: (severity: 'ERROR' | 'WARN', rule: string, message: string) => void,
): void {
  const plannedModules = new Set(contract.modules.map((module) => normalizeModuleName(module.name)));
  const targetModule = decision.targetModule ? normalizeModuleName(decision.targetModule) : undefined;
  const hasTargetModule = targetModule ? plannedModules.has(targetModule) : false;
  const bindings = contract.referenceBindings.filter((binding) =>
    !targetModule || normalizeModuleName(binding.targetModule ?? '') === targetModule
      || decision.evidenceSymbols.includes(binding.referenceSymbol));

  if (decision.decision !== 'NEW' && targetModule
    && plannedModules.size > 0 && !hasTargetModule && contract.architectureDecisions.length === 0) {
    add('ERROR', 'REF-DOMAIN-OWNER-CONFLICT',
      `Reference evidence assigns this capability to module ${decision.targetModule}, but the plan uses ${Array.from(plannedModules).join(', ')} without an architecture decision.`);
  }

  if ((decision.decision === 'REUSE' || decision.decision === 'EXTEND') && bindings.length === 0) {
    add('ERROR', 'REF-DUPLICATE-CAPABILITY',
      `Reference decision is ${decision.decision}, but the contract has no binding to ${decision.evidenceSymbols.slice(0, 4).join(', ') || 'the matching reference symbols'}.`);
  }

  if (decision.decision === 'ARCHITECTURE_DECISION_REQUIRED' && contract.architectureDecisions.length === 0) {
    add('ERROR', 'REF-ARCHITECTURE-DECISION-MISSING',
      `Reference evidence requires an explicit architecture decision: ${decision.reason}`);
  }

  if (decision.decision !== 'NEW' && targetModule && plannedModules.size > 0 && !hasTargetModule) {
    const hasEntry = contract.integrations.some((integration) =>
      normalizeModuleName(integration.targetModule ?? '') === targetModule && Boolean(integration.entrypoint));
    if (!hasEntry) {
      add('ERROR', 'INTEGRATION-ENTRY-MISSING',
        `Plan does not define a concrete integration entrypoint into reference module ${decision.targetModule}.`);
    }
  }

  if (decision.decision !== 'NEW' && symbols.length === 0) {
    add('WARN', 'REFERENCE-BINDING-EVIDENCE-MISSING',
      'Reference decision requires reuse/extension, but no reference symbols were returned.');
  }
}

function inferPlanContract(sourceText: string, sourceHash: string): PlanContract {
  const modules = inferModules(sourceText);
  const primaryModuleId = modules[0]?.id;
  const tables = uniqueMatches(sourceText, /\b(?:blade|work|sk)_[a-z0-9_]+\b/gi);
  const entityNames = uniqueMatches(sourceText, /\bEntity\s*[:\uff1a]\s*`?([A-Z][A-Za-z0-9]{2,})`?/giu, 1)
    .concat(uniqueMatches(sourceText, /\bclass\s+([A-Z][A-Za-z0-9]+)\s+(?:extends|implements|\{)/g, 1));
  entityNames.push(...uniqueMatches(sourceText, /\u7c7b\u540d\s*[:\uff1a]\s*`?([A-Z][A-Za-z0-9]{2,})`?/giu, 1));
  const uniqueEntities = Array.from(new Set(entityNames));
  const fields = uniqueMatches(sourceText, /\b(state|workState|status|flowId|taskId|beginTime|endTime)\b/g, 1);
  const entities: PlanEntityContract[] = uniqueEntities.map((name, index) => ({
    id: `entity.${toId(name)}`,
    name,
    ...(primaryModuleId ? { moduleId: primaryModuleId } : {}),
    ...(tables[index] || tables[0] ? { table: tables[index] || tables[0] } : {}),
    fields,
  }));

  const domains: PlanDomainContract[] = modules.map((module) => ({
    id: `domain.${toId(module.name)}`,
    name: module.name,
    ownerModuleIds: [module.id],
  }));
  if (domains.length === 0 && entities.length > 0) {
    domains.push({ id: 'domain.main', name: 'main', ownerModuleIds: [] });
  }
  const aggregates: PlanAggregateContract[] = entities.map((entity) => {
    const id = `aggregate.${toId(entity.name)}`;
    entity.aggregateId = id;
    return {
      id,
      name: `${entity.name}Aggregate`,
      domainId: domains[0]?.id ?? 'domain.main',
      rootEntityId: entity.id,
    };
  });

  const states: PlanStateContract[] = [];
  if (/\b(?:state|workState|status)\b/i.test(sourceText) || /\u72b6\u6001(?:\u673a|\u6d41\u8f6c)?/u.test(sourceText)) {
    states.push({
      id: 'state.main',
      name: fields.find((field) => /state|status/i.test(field)) ?? 'businessState',
      values: inferStateValues(sourceText),
    });
  }

  const integrations: PlanIntegrationContract[] = [];
  const controller = uniqueMatches(sourceText, /\b([A-Z][A-Za-z0-9]+Controller)\b/g, 1)[0];
  const apiPath = uniqueMatches(sourceText, /\/(?:api\/)?[a-z][a-z0-9_\-/{}]*/gi)[0];
  if (/\bFeign\b/i.test(sourceText)) {
    integrations.push({ id: 'integration.feign', type: 'FEIGN', ...(controller || apiPath ? { entrypoint: controller || apiPath } : {}) });
  }
  if (/\bflowId\b|\u5de5\u4f5c\u6d41|\u5ba1\u6279/u.test(sourceText)) {
    integrations.push({ id: 'integration.workflow', type: 'WORKFLOW', ...(controller || apiPath ? { entrypoint: controller || apiPath } : {}) });
  }
  if (/\bController\b|\bREST\b|\bAPI\b/.test(sourceText)) {
    integrations.push({ id: 'integration.api', type: 'API', ...(controller || apiPath ? { entrypoint: controller || apiPath } : {}) });
  }

  const deliverables = inferDeliverables(sourceText, primaryModuleId);
  const referenceBindings = inferReferenceBindings(sourceText, entities);
  const architectureDecisions = inferArchitectureDecisions(sourceText);

  const identity = inferIdentity(modules, entities, tables);
  const fieldContracts = inferFieldContracts(entities, sourceText);
  for (const entity of entities) {
    entity.fieldIds = fieldContracts.filter((field) => field.entityId === entity.id).map((field) => field.id);
  }
  const enrichedDeliverables = enrichDeliverables(deliverables, identity, entities);

  return normalizeContract({
    contractVersion: PLAN_CONTRACT_VERSION,
    sourceHash,
    sourceMode: 'LEGACY_INFERRED',
    rulesetVersion: PLAN_CONTRACT_RULESET_VERSION,
    identity,
    fields: fieldContracts,
    domains,
    modules,
    aggregates,
    entities,
    states,
    integrations,
    deliverables: enrichedDeliverables,
    referenceBindings,
    architectureDecisions,
  }, sourceHash);
}

function inferModules(sourceText: string): PlanModuleContract[] {
  const names = new Set<string>();
  for (const name of uniqueMatches(sourceText, /\bmodule(?:Name)?\s*[:=]\s*`?([a-z][a-z0-9-]*)`?/gi, 1)) names.add(name);
  for (const name of uniqueMatches(sourceText, /\u6a21\u5757(?:\u540d|\u540d\u79f0)?\s*[:\uff1a=]\s*`?([a-z][a-z0-9-]*)`?/giu, 1)) names.add(name);
  for (const name of uniqueMatches(sourceText, /\borg\.springblade\.([a-z][a-z0-9]*)\b/gi, 1)) names.add(name);
  for (const name of uniqueMatches(sourceText, /\bblade-(?:service|service-api)\/blade-([a-z][a-z0-9-]*?)(?:-api)?\b/gi, 1)) names.add(name);
  return Array.from(names).map((name) => ({
    id: `module.${toId(name)}`,
    name: normalizeModuleName(name),
    basePackage: `org.springblade.${normalizeModuleName(name).replace(/-/g, '')}`,
    kind: 'UNKNOWN',
  }));
}

function inferDeliverables(sourceText: string, moduleId?: string): PlanDeliverableContract[] {
  const specs: Array<[PlanDeliverableContract['kind'], RegExp]> = [
    ['DDL', /\bDDL\b|\bCREATE\s+TABLE\b|\u5efa\u8868|\u6570\u636e\u5e93/u],
    ['ENTITY', /\bEntity\b|\u5b9e\u4f53/u],
    ['VO', /\bVO\b|\u89c6\u56fe\u5bf9\u8c61/u],
    ['MAPPER', /\bMapper\b/],
    ['SERVICE', /\bService\b/],
    ['CONTROLLER', /\bController\b|\bREST\b/],
    ['FEIGN', /\bFeign\b/i],
    ['EXCEL', /\bExcel\b|\u5bfc\u5165|\u5bfc\u51fa/iu],
    ['CONFIG', /\bbootstrap\.yml\b|\bapplication\.yml\b|\u914d\u7f6e/u],
  ];
  return specs.flatMap(([kind, pattern]) => pattern.test(sourceText)
    ? [{ id: `deliverable.${kind.toLowerCase()}`, kind, name: kind, ...(moduleId ? { moduleId } : {}) }]
    : []);
}

function inferReferenceBindings(sourceText: string, entities: PlanEntityContract[]): PlanReferenceBinding[] {
  if (!/\b(?:REUSE|EXTEND)\b|\u590d\u7528|\u6269\u5c55/iu.test(sourceText)) return [];
  const symbols = uniqueMatches(sourceText, /\b(WorkOrderTask|SkRiskDates|[A-Z][A-Za-z0-9]+(?:Service|Client))\b/g, 1);
  const target = entities[0]?.id;
  if (!target) return [];
  return symbols.map((symbol, index) => ({
    id: `binding.${index + 1}`,
    planElementId: target,
    referenceSymbol: symbol,
    decision: /\bREUSE\b|\u590d\u7528/iu.test(sourceText) ? 'REUSE' : 'EXTEND',
  }));
}

function inferArchitectureDecisions(sourceText: string): PlanArchitectureDecision[] {
  if (!/\bADR[- ]?\d*\b|\u67b6\u6784\u51b3\u7b56/iu.test(sourceText)) return [];
  return [{
    id: 'adr.1',
    decision: 'Legacy Markdown architecture decision',
    rationale: 'See the architecture decision section in the reviewed plan.',
    evidence: [],
  }];
}

function inferIdentity(
  modules: PlanModuleContract[],
  entities: PlanEntityContract[],
  tables: string[],
): PlanIdentityContract {
  const moduleName = normalizeModuleName(modules[0]?.name || 'generated');
  const entityName = entities[0]?.name || 'GeneratedEntity';
  const tableName = tables[0] || entities[0]?.table || `blade_${moduleName.replace(/-/g, '_')}`;
  const basePackage = modules[0]?.basePackage || `org.springblade.${moduleName.replace(/-/g, '')}`;
  return {
    moduleName,
    entityName,
    tableName,
    basePackage,
    apiModuleName: `blade-${moduleName}-api`,
    serviceModuleName: `blade-${moduleName}`,
    serviceName: `blade-${moduleName}`,
  };
}

function inferFieldContracts(entities: PlanEntityContract[], sourceText: string): PlanFieldContract[] {
  const result: PlanFieldContract[] = [];
  for (const entity of entities) {
    const names = entity.fields.length > 0 ? entity.fields : uniqueMatches(sourceText,
      /\b([a-z][A-Za-z0-9]{1,40})\s*[:?]\s*(?:String|Long|Integer|Date|Boolean|BigDecimal|LocalDateTime)\b/g, 1);
    for (const name of names) {
      if (result.some((field) => field.entityId === entity.id && field.name === name)) continue;
      const typeMatch = sourceText.match(new RegExp(`\\b${escapeRegExp(name)}\\s*[:\uFF1A]\\s*(String|Long|Integer|Date|Boolean|BigDecimal|LocalDateTime)\\b`, 'i'));
      const javaType = typeMatch?.[1] || inferJavaType(name);
      result.push({
        id: `field.${toId(entity.name)}.${toId(name)}`,
        entityId: entity.id,
        name,
        columnName: camelToSnake(name),
        javaType,
        required: new RegExp(`\\b${escapeRegExp(name)}\\b[^\\n]{0,40}(?:\u5fc5\u586b|\u975e\u7a7a|NOT NULL)`, 'i').test(sourceText),
        role: /Name$|Label$|Text$|Display$/.test(name) ? 'DERIVED' : 'PERSISTENT',
        evidence: sourceText ? `legacy-plan:${name}` : 'legacy-contract',
      });
    }
  }
  return result;
}

function enrichDeliverables(
  deliverables: PlanDeliverableContract[],
  identity: PlanIdentityContract,
  entities: PlanEntityContract[],
): PlanDeliverableContract[] {
  const entityName = entities[0]?.name || identity.entityName;
  const specs: Record<PlanDeliverableContract['kind'], { className?: string; side: PlanDeliverableContract['moduleSide']; provides: string[]; requires: string[] }> = {
    DDL: { side: 'IMPL', provides: [], requires: [] },
    ENTITY: { className: entityName, side: 'API', provides: [entityName], requires: [] },
    VO: { className: `${entityName}VO`, side: 'API', provides: [`${entityName}VO`, `${entityName}QVO`, `${entityName}IVO`, `${entityName}UVO`, `${entityName}EVO`], requires: [entityName] },
    MAPPER: { className: `${entityName}Mapper`, side: 'IMPL', provides: [`${entityName}Mapper`], requires: [entityName, `${entityName}VO`, `${entityName}QVO`] },
    SERVICE: { className: `I${entityName}Service`, side: 'IMPL', provides: [`I${entityName}Service`, `${entityName}ServiceImpl`], requires: [entityName, `${entityName}VO`, `${entityName}QVO`, `${entityName}IVO`, `${entityName}UVO`] },
    WRAPPER: { className: `${entityName}Wrapper`, side: 'IMPL', provides: [`${entityName}Wrapper`], requires: [entityName, `${entityName}VO`] },
    CONTROLLER: { className: `${entityName}Controller`, side: 'IMPL', provides: [`${entityName}Controller`], requires: [`I${entityName}Service`, `${entityName}VO`, `${entityName}QVO`, `${entityName}IVO`, `${entityName}UVO`] },
    FEIGN: { className: `${entityName}Client`, side: 'API', provides: [`${entityName}Client`], requires: [`${entityName}VO`] },
    EXCEL: { className: `${entityName}Excel`, side: 'API', provides: [`${entityName}Excel`], requires: [entityName] },
    CONFIG: { side: 'IMPL', provides: [], requires: [] },
    OTHER: { side: 'UNKNOWN', provides: [], requires: [] },
  };
  const enriched = deliverables.map((deliverable) => {
    const spec = specs[deliverable.kind];
    return {
      ...deliverable,
      ...(deliverable.className || spec.className ? { className: deliverable.className || spec.className } : {}),
      moduleSide: deliverable.moduleSide || spec.side,
      action: deliverable.action || 'CREATE',
      providesTypes: deliverable.providesTypes ?? spec.provides,
      requiresTypes: deliverable.requiresTypes ?? spec.requires,
    };
  });
  return enriched;
}

function inferJavaType(name: string): string {
  if (/Id$|User$|Dept$|Count$/i.test(name)) return 'Long';
  if (/Time$|Date$/i.test(name)) return 'Date';
  if (/State$|Status$|Type$|Level$|^is[A-Z]/.test(name)) return 'Integer';
  if (/Amount$|Price$|Rate$/i.test(name)) return 'BigDecimal';
  return 'String';
}

function camelToSnake(value: string): string {
  return value.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function parseIdentity(value: unknown): PlanIdentityContract | null {
  if (!isRecord(value)) return null;
  const keys = ['moduleName', 'entityName', 'tableName', 'basePackage', 'apiModuleName', 'serviceModuleName', 'serviceName'] as const;
  if (!keys.every((key) => nonBlankString(value[key]))) return null;
  return Object.fromEntries(keys.map((key) => [key, String(value[key])])) as unknown as PlanIdentityContract;
}

function parseField(value: unknown): PlanFieldContract | null {
  if (!isRecord(value) || !nonBlankString(value.id) || !nonBlankString(value.entityId)
    || !nonBlankString(value.name) || !nonBlankString(value.columnName) || !nonBlankString(value.javaType)
    || typeof value.required !== 'boolean' || (value.role !== 'PERSISTENT' && value.role !== 'DERIVED')) return null;
  if (value.evidence != null && typeof value.evidence !== 'string') return null;
  return value as unknown as PlanFieldContract;
}

function parsePlanContract(value: unknown, sourceHash: string): PlanContract | null {
  if (!isRecord(value) || (value.contractVersion !== PLAN_CONTRACT_VERSION && value.contractVersion !== '1.0')) return null;
  const domains = parseArray(value.domains, parseDomain);
  const modules = parseArray(value.modules, parseModule);
  const aggregates = parseArray(value.aggregates, parseAggregate);
  const entities = parseArray(value.entities, parseEntity);
  const states = parseArray(value.states, parseState);
  const integrations = parseArray(value.integrations, parseIntegration);
  const deliverables = parseArray(value.deliverables, parseDeliverable);
  const referenceBindings = parseArray(value.referenceBindings, parseReferenceBinding);
  const architectureDecisions = parseArray(value.architectureDecisions, parseArchitectureDecision);
  if (!domains || !modules || !aggregates || !entities || !states || !integrations || !deliverables
    || !referenceBindings || !architectureDecisions) return null;
  const identity = parseIdentity(value.identity) ?? inferIdentity(modules, entities,
    entities.map((entity) => entity.table).filter((table): table is string => Boolean(table)));
  const fields = parseArray(value.fields, parseField) ?? inferFieldContracts(entities, '');
  const sourceMode = value.sourceMode === 'STRUCTURED' ? 'STRUCTURED' : 'LEGACY_INFERRED';
  return normalizeContract({
    contractVersion: PLAN_CONTRACT_VERSION,
    sourceHash,
    sourceMode,
    ...(typeof value.referenceSnapshotId === 'string' ? { referenceSnapshotId: value.referenceSnapshotId } : {}),
    rulesetVersion: typeof value.rulesetVersion === 'string' && value.rulesetVersion
      ? value.rulesetVersion : PLAN_CONTRACT_RULESET_VERSION,
    identity,
    fields,
    domains,
    modules,
    aggregates,
    entities,
    states,
    integrations,
    deliverables: enrichDeliverables(deliverables, identity, entities),
    referenceBindings,
    architectureDecisions,
  }, sourceHash);
}

function parseDomain(value: unknown): PlanDomainContract | null {
  if (!isRecord(value) || !nonBlankString(value.id) || !nonBlankString(value.name)
    || !isStringArray(value.ownerModuleIds)) return null;
  return { id: value.id, name: value.name, ownerModuleIds: value.ownerModuleIds };
}

function parseAggregate(value: unknown): PlanAggregateContract | null {
  if (!isRecord(value) || !nonBlankString(value.id) || !nonBlankString(value.name)
    || !nonBlankString(value.domainId) || !nonBlankString(value.rootEntityId)) return null;
  return { id: value.id, name: value.name, domainId: value.domainId, rootEntityId: value.rootEntityId };
}

function parseModule(value: unknown): PlanModuleContract | null {
  if (!isRecord(value) || !nonBlankString(value.id) || !nonBlankString(value.name)
    || (value.kind !== 'EXISTING' && value.kind !== 'NEW' && value.kind !== 'UNKNOWN')) return null;
  if (value.basePackage != null && typeof value.basePackage !== 'string') return null;
  return { id: value.id, name: value.name, kind: value.kind, ...(value.basePackage ? { basePackage: value.basePackage } : {}) };
}

function parseEntity(value: unknown): PlanEntityContract | null {
  if (!isRecord(value) || !nonBlankString(value.id) || !nonBlankString(value.name)
    || !isStringArray(value.fields)) return null;
  for (const key of ['moduleId', 'aggregateId', 'table'] as const) {
    if (value[key] != null && typeof value[key] !== 'string') return null;
  }
  const moduleId = typeof value.moduleId === 'string' ? value.moduleId : undefined;
  const aggregateId = typeof value.aggregateId === 'string' ? value.aggregateId : undefined;
  const table = typeof value.table === 'string' ? value.table : undefined;
  if (value.fieldIds != null && !isStringArray(value.fieldIds)) return null;
  return {
    id: value.id, name: value.name, fields: value.fields,
    ...(isStringArray(value.fieldIds) ? { fieldIds: value.fieldIds } : {}),
    ...(moduleId ? { moduleId } : {}),
    ...(aggregateId ? { aggregateId } : {}),
    ...(table ? { table } : {}),
  };
}

function parseState(value: unknown): PlanStateContract | null {
  if (!isRecord(value) || !nonBlankString(value.id) || !nonBlankString(value.name) || !isStringArray(value.values)) return null;
  if (value.ownerId != null && typeof value.ownerId !== 'string') return null;
  if (value.transitions != null && (!Array.isArray(value.transitions)
    || !value.transitions.every((entry) => isRecord(entry) && nonBlankString(entry.from)
      && nonBlankString(entry.to) && nonBlankString(entry.trigger)))) return null;
  if (value.referenceField != null && typeof value.referenceField !== 'string') return null;
  return {
    id: value.id, name: value.name, values: value.values,
    ...(value.ownerId ? { ownerId: value.ownerId } : {}),
    ...(Array.isArray(value.transitions) ? { transitions: value.transitions as unknown as PlanStateTransitionContract[] } : {}),
    ...(typeof value.referenceField === 'string' ? { referenceField: value.referenceField } : {}),
  };
}

function parseIntegration(value: unknown): PlanIntegrationContract | null {
  if (!isRecord(value) || !nonBlankString(value.id)
    || !['API', 'FEIGN', 'WORKFLOW', 'EVENT', 'OTHER'].includes(String(value.type))) return null;
  for (const key of ['sourceModule', 'targetModule', 'entrypoint'] as const) {
    if (value[key] != null && typeof value[key] !== 'string') return null;
  }
  return value as unknown as PlanIntegrationContract;
}

function parseDeliverable(value: unknown): PlanDeliverableContract | null {
  if (!isRecord(value) || !nonBlankString(value.id) || !nonBlankString(value.name)
    || !['DDL', 'ENTITY', 'VO', 'MAPPER', 'SERVICE', 'WRAPPER', 'CONTROLLER', 'FEIGN', 'EXCEL', 'CONFIG', 'OTHER']
      .includes(String(value.kind))) return null;
  if (value.moduleId != null && typeof value.moduleId !== 'string') return null;
  if (value.className != null && typeof value.className !== 'string') return null;
  if (value.moduleSide != null && !['API', 'IMPL', 'DOC', 'UNKNOWN'].includes(String(value.moduleSide))) return null;
  if (value.action != null && !['CREATE', 'MODIFY', 'EXTEND', 'PROHIBIT'].includes(String(value.action))) return null;
  if (value.providesTypes != null && !isStringArray(value.providesTypes)) return null;
  if (value.requiresTypes != null && !isStringArray(value.requiresTypes)) return null;
  return value as unknown as PlanDeliverableContract;
}

function parseReferenceBinding(value: unknown): PlanReferenceBinding | null {
  if (!isRecord(value) || !nonBlankString(value.id) || !nonBlankString(value.planElementId)
    || !nonBlankString(value.referenceSymbol) || (value.decision !== 'REUSE' && value.decision !== 'EXTEND')) return null;
  if (value.targetModule != null && typeof value.targetModule !== 'string') return null;
  return value as unknown as PlanReferenceBinding;
}

function parseArchitectureDecision(value: unknown): PlanArchitectureDecision | null {
  if (!isRecord(value) || !nonBlankString(value.id) || !nonBlankString(value.decision)
    || !nonBlankString(value.rationale) || !isStringArray(value.evidence)) return null;
  return value as unknown as PlanArchitectureDecision;
}

function normalizeContract(contract: PlanContract, sourceHash: string): PlanContract {
  return {
    contractVersion: PLAN_CONTRACT_VERSION,
    sourceHash,
    sourceMode: contract.sourceMode ?? 'LEGACY_INFERRED',
    ...(contract.referenceSnapshotId ? { referenceSnapshotId: contract.referenceSnapshotId } : {}),
    rulesetVersion: contract.rulesetVersion || PLAN_CONTRACT_RULESET_VERSION,
    identity: { ...contract.identity },
    fields: contract.fields.map((field) => ({ ...field })),
    domains: contract.domains.map((domain) => ({ ...domain, ownerModuleIds: [...domain.ownerModuleIds] })),
    modules: contract.modules.map((module) => ({ ...module })),
    aggregates: contract.aggregates.map((aggregate) => ({ ...aggregate })),
    entities: contract.entities.map((entity) => ({ ...entity, fields: [...entity.fields], ...(entity.fieldIds ? { fieldIds: [...entity.fieldIds] } : {}) })),
    states: contract.states.map((state) => ({ ...state, values: [...state.values], ...(state.transitions ? { transitions: state.transitions.map((transition) => ({ ...transition })) } : {}) })),
    integrations: contract.integrations.map((integration) => ({ ...integration })),
    deliverables: contract.deliverables.map((deliverable) => ({ ...deliverable, ...(deliverable.providesTypes ? { providesTypes: [...deliverable.providesTypes] } : {}), ...(deliverable.requiresTypes ? { requiresTypes: [...deliverable.requiresTypes] } : {}) })),
    referenceBindings: contract.referenceBindings.map((binding) => ({ ...binding })),
    architectureDecisions: contract.architectureDecisions.map((decision) => ({ ...decision, evidence: [...decision.evidence] })),
  };
}

function collectContractIds(contract: PlanContract): string[] {
  return [
    ...contract.domains,
    ...contract.modules,
    ...contract.aggregates,
    ...contract.entities,
    ...contract.states,
    ...contract.integrations,
    ...contract.deliverables,
    ...contract.referenceBindings,
    ...contract.architectureDecisions,
  ].map((item) => item.id);
}

function hasMixedAggregateName(entities: PlanEntityContract[], symbols: ReferenceSymbol[]): boolean {
  const hasWorkOrderEvidence = symbols.some((symbol) => /workorder|hotwork|task/i.test(symbol.simpleName));
  const hasPeriodEvidence = symbols.some((symbol) => /special|period|riskdate|holiday/i.test(symbol.simpleName));
  if (!hasWorkOrderEvidence || !hasPeriodEvidence) return false;
  return entities.some((entity) => {
    const lower = entity.name.toLowerCase();
    return /hot|work|task|order/.test(lower) && /special|period|risk|date|holiday/.test(lower);
  });
}

function danglingAnomalyIsRelevant(message: string, sourceLower: string, contract: PlanContract): boolean {
  const moduleMatch = message.toLowerCase().match(/blade-(?:service-api|service)\/blade-([a-z0-9-]+)/);
  if (!moduleMatch) return true;
  const missing = normalizeModuleName(moduleMatch[1].replace(/-api$/, ''));
  if (contract.modules.some((module) => normalizeModuleName(module.name) === missing)) return true;
  if (sourceLower.includes(missing)) return true;
  if (missing.includes('specialperiod')) {
    return /special|period|holiday|riskdate/.test(sourceLower)
      || /\u7279\u6b8a\u65f6\u6bb5|\u8282\u5047\u65e5|\u516c\u4f11\u65e5/u.test(sourceLower);
  }
  return false;
}

function inferStateValues(sourceText: string): string[] {
  return uniqueMatches(sourceText, /\b(PENDING|APPROVED|REJECTED|COMPLETED|CANCELLED|DRAFT|RUNNING|CLOSED)\b/g, 1);
}

function uniqueMatches(value: string, pattern: RegExp, group = 0): string[] {
  const result: string[] = [];
  for (const match of value.matchAll(pattern)) {
    const candidate = match[group]?.trim();
    if (candidate && !result.includes(candidate)) result.push(candidate);
  }
  return result;
}

function normalizeModuleName(value: string): string {
  return value.toLowerCase().replace(/^blade-/, '').replace(/-api$/, '').trim();
}

function toId(value: string): string {
  return value.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/[^A-Za-z0-9]+/g, '-').toLowerCase().replace(/^-|-$/g, '');
}

function hashText(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}



export function hashPlanContent(planContent: string): string {
  return hashText(stripPlanContractBlock(planContent));
}

export function hashPlanContract(contract: PlanContract): string {
  const normalized = normalizeContract(contract, contract.sourceHash);
  const { sourceHash: _sourceHash, referenceSnapshotId: _referenceSnapshotId, ...structural } = normalized;
  return hashText(stableStringify(structural));
}

export interface SubPlanDescriptorHashMaterial {
  id: string;
  index: number;
  title: string;
  contentHash: string;
  prerequisites: string[];
  deliverableIds: string[];
  contractHash: string;
  referencedElementIds: string[];
  inputTypes: string[];
  outputTypes: string[];
}

export interface PlanBundleHashMaterial {
  projectId: string;
  writeTarget: 'ISOLATED' | 'REAL';
  generationIdentity: PlanIdentityContract;
  masterPlan: { id: string; version: number; contentHash: string };
  contractHash: string;
  subPlans: SubPlanDescriptorHashMaterial[];
}

/** Hash of the exact reviewed sub-plan descriptor, including its DAG and contract slice. */
export function hashSubPlanDescriptor(material: SubPlanDescriptorHashMaterial): string {
  return hashText(stableStringify(normalizeSubPlanDescriptor(material)));
}

function normalizeSubPlanDescriptor(material: SubPlanDescriptorHashMaterial): SubPlanDescriptorHashMaterial {
  return {
    ...material,
    prerequisites: [...material.prerequisites].sort(),
    deliverableIds: [...material.deliverableIds].sort(),
    referencedElementIds: [...material.referencedElementIds].sort(),
    inputTypes: [...material.inputTypes].sort(),
    outputTypes: [...material.outputTypes].sort(),
  };
}

/** Cross-language canonical hash for one complete reviewed Part A -> Part B bundle. */
export function hashPlanBundle(material: PlanBundleHashMaterial): string {
  const normalized: PlanBundleHashMaterial = {
    projectId: material.projectId,
    writeTarget: material.writeTarget,
    generationIdentity: { ...material.generationIdentity },
    masterPlan: { ...material.masterPlan },
    contractHash: material.contractHash,
    subPlans: material.subPlans
      .map(normalizeSubPlanDescriptor)
      .sort((left, right) => left.index - right.index || compareCodeUnits(left.id, right.id)),
  };
  return hashText(stableStringify(normalized));
}

export function withContractReviewMetadata(
  contract: PlanContract,
  metadata: { referenceSnapshotId?: string; sourceMode?: PlanContract['sourceMode']; rulesetVersion?: string },
): PlanContract {
  return normalizeContract({
    ...contract,
    sourceMode: metadata.sourceMode ?? contract.sourceMode,
    referenceSnapshotId: metadata.referenceSnapshotId ?? contract.referenceSnapshotId,
    rulesetVersion: metadata.rulesetVersion ?? contract.rulesetVersion,
  }, contract.sourceHash);
}

export function renderCanonicalContractSummary(planContent: string, contract: PlanContract): string {
  const narrative = stripPlanContractBlock(planContent)
    .replace(/\n*## Canonical implementation contract[\s\S]*$/i, '')
    .trimEnd();
  const fields = contract.fields.map((field) =>
    `| ${field.name} | ${field.columnName} | ${field.javaType} | ${field.required ? 'yes' : 'no'} | ${field.role} |`).join('\n');
  const deliverables = contract.deliverables.map((item) =>
    `- ${item.id}: ${item.kind}${item.className ? ` / ${item.className}` : ''} / ${item.moduleSide ?? 'UNKNOWN'}`).join('\n');
  const integrations = contract.integrations.length > 0
    ? contract.integrations.map((item) => `- ${item.id}: ${item.type}, ${item.sourceModule ?? contract.identity.moduleName} -> ${item.targetModule ?? '(local)'}, entry=${item.entrypoint ?? '(missing)'}`).join('\n')
    : '- none';
  const summary = `## Canonical implementation contract\n\n### Identity\n\n- moduleName: ${contract.identity.moduleName}\n- entityName: ${contract.identity.entityName}\n- tableName: ${contract.identity.tableName}\n- basePackage: ${contract.identity.basePackage}\n- apiModuleName: ${contract.identity.apiModuleName}\n- serviceModuleName: ${contract.identity.serviceModuleName}\n- serviceName: ${contract.identity.serviceName}\n\n### Fields\n\n| Java field | SQL column | Java type | required | role |\n|---|---|---|---|---|\n${fields || '| (none) | (none) | (none) | no | PERSISTENT |'}\n\n### Deliverables\n\n${deliverables || '- none'}\n\n### Integrations\n\n${integrations}`;
  return upsertPlanContractBlock(`${narrative}\n\n${summary}`, contract);
}

export function validateNarrativeContractConsistency(planContent: string, contract: PlanContract): DeterministicPlanIssue[] {
  const narrative = stripPlanContractBlock(planContent);
  const authoritativeNarrative = narrative.replace(
    /(?:^|\n)##\s*(?:Requirement analysis|\u9700\u6c42\u5206\u6790)\s*\n[\s\S]*?(?=\n##\s|$)/i,
    '\n',
  );
  const issues: DeterministicPlanIssue[] = [];
  const checks: Array<[string, string, string]> = [
    ['PLAN-NARRATIVE-MODULE-DRIFT', 'moduleName', contract.identity.moduleName],
    ['PLAN-NARRATIVE-ENTITY-DRIFT', 'entityName', contract.identity.entityName],
    ['PLAN-NARRATIVE-TABLE-DRIFT', 'tableName', contract.identity.tableName],
    ['PLAN-NARRATIVE-PACKAGE-DRIFT', 'basePackage', contract.identity.basePackage],
  ];
  for (const [rule, label, expected] of checks) {
    const expressions = label === 'moduleName'
      ? [/(?:^|\n)\s*(?:[-*]\s*)?moduleName\s*[:\uFF1A=]\s*`?([a-z][a-z0-9-]*)/gi, /(?:^|\n)\s*(?:[-*]\s*)?\u6a21\u5757\u540d\s*[:\uFF1A=]\s*`?([a-z][a-z0-9-]*)/giu]
      : label === 'entityName'
        ? [/(?:^|\n)\s*(?:[-*]\s*)?entityName\s*[:\uFF1A=]\s*`?([A-Z][A-Za-z0-9]*)/g, /(?:^|\n)\s*(?:[-*]\s*)?\u5b9e\u4f53\u540d\s*[:\uFF1A=]\s*`?([A-Z][A-Za-z0-9]*)/gu]
        : label === 'tableName'
          ? [/(?:^|\n)\s*(?:[-*]\s*)?tableName\s*[:\uFF1A=]\s*`?([a-z][a-z0-9_]*)/gi, /(?:^|\n)\s*(?:[-*]\s*)?\u8868\u540d\s*[:\uFF1A=]\s*`?([a-z][a-z0-9_]*)/gu]
          : [/(?:^|\n)\s*(?:[-*]\s*)?basePackage\s*[:\uFF1A=]\s*`?(org\.springblade\.[A-Za-z0-9_.]+)/gi, /(?:^|\n)\s*(?:[-*]\s*)?\u5305\u8def\u5f84\s*[:\uFF1A=]\s*`?(org\.springblade\.[A-Za-z0-9_.]+)/gu];
    const declared = new Set(expressions.flatMap((pattern) => uniqueMatches(authoritativeNarrative, pattern, 1)));
    for (const value of declared) {
      if (value !== expected) issues.push({ severity: 'ERROR', rule, message: `${label} declares ${value}, canonical value is ${expected}.` });
    }
  }
  const fieldNames = new Set(contract.fields.map((field) => field.name));
  for (const entity of contract.entities) {
    for (const field of entity.fields) {
      if (!fieldNames.has(field)) issues.push({ severity: 'ERROR', rule: 'PLAN-NARRATIVE-FIELD-DRIFT', message: `Entity ${entity.name} references field ${field} outside canonical field inventory.` });
    }
  }
  return issues;
}

function compareCodeUnits(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

function stableStringify(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (isRecord(value)) return `{${Object.keys(value).filter((key) => value[key] !== undefined).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
}

function parseArray<T>(value: unknown, parser: (entry: unknown) => T | null): T[] | null {
  if (!Array.isArray(value)) return null;
  const parsed = value.map(parser);
  return parsed.some((entry) => !entry) ? null : parsed as T[];
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
