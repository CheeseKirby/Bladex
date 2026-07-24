import type { ExecutionTimeline, GeneratedFileSummary } from './api';

// === 方案工作流状态 ===
export type WorkflowState =
  | 'DRAFT'
  | 'ANALYZING'
  | 'ANALYZED'
  | 'PLANNING'
  | 'PLAN_GENERATED'
  | 'REVIEWING'
  | 'REVIEWED'
  | 'PLAN_CONFIRMED'
  | 'SPLITTING'
  | 'SUBPLANS_GENERATED'
  | 'SUBPLAN_REVIEWING'
  | 'SUBPLANS_REVIEWED'
  | 'SUBPLANS_CONFIRMED'
  | 'TRANSMITTING'
  | 'TRANSMITTED';

// === 模块类型 ===
export type ModuleType =
  | 'ENTITY'
  | 'API'
  | 'PAGE'
  | 'FLOW'
  | 'JOB'
  | 'FEIGN'
  | 'EXCEL'
  | 'CONFIG';

// === 模块配置 ===
export interface FieldConfig {
  name: string;
  type: string;
  comment: string;
  nullable: boolean;
  length?: number;
}

export interface EndpointConfig {
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
  path: string;
  summary: string;
  params: string;
  returnType: string;
}

export interface ModuleConfig {
  // ENTITY
  tableName?: string;
  moduleName?: string;
  fields?: FieldConfig[];
  extendBaseEntity?: boolean;
  needVO?: boolean;
  needExcel?: boolean;
  // API
  pathPrefix?: string;
  endpoints?: EndpointConfig[];
  needAuth?: boolean;
  needLog?: boolean;
  // PAGE
  pageType?: 'list' | 'form' | 'detail' | 'dashboard';
  pageName?: string;
  needSearch?: boolean;
  needPagination?: boolean;
  // FLOW
  processName?: string;
  nodes?: string[];
  needForm?: boolean;
  // JOB
  jobName?: string;
  cronExpression?: string;
  jobHandler?: string;
  // FEIGN
  targetService?: string;
  apiPrefix?: string;
  methods?: { name: string; path: string; method: string; params: string; returnType: string }[];
  // EXCEL
  entityName?: string;
  needImport?: boolean;
  needExport?: boolean;
  needTemplate?: boolean;
  // CONFIG
  configType?: 'datasource' | 'route' | 'security' | 'tenant';
  entries?: { key: string; value: string }[];
}

// === 拖拽模块 ===
export interface DraggedModule {
  id: string;
  type: ModuleType;
  name: string;
  icon: string;
  color: string;
  config: ModuleConfig;
}

// === 方案 ===
export interface MasterPlan {
  id: string;
  projectId: string;
  version: number;
  planContent: string;
  reviewedContent?: string;
  reviewChangeLog?: ChangeLogEntry[];
  reviewStatus?: ReviewFinalStatus;
  reviewAudit?: ReviewAuditEvidence;
  reviewId?: string;
  activeReviewId?: string;
  contractHash?: string;
  status: WorkflowState;
  llmModel?: string;
  llmTokensUsed?: number;
  createdAt?: string;
}

// === 子方案 ===
export interface SubPlan {
  id: string;
  masterPlanId: string;
  index: number;
  title: string;
  planContent: string;
  reviewedContent?: string;
  reviewChangeLog?: ChangeLogEntry[];
  reviewStatus?: ReviewFinalStatus;
  reviewAudit?: ReviewAuditEvidence;
  reviewId?: string;
  contractHash?: string;
  deliverableIds?: string[];
  referencedElementIds?: string[];
  inputTypes?: string[];
  outputTypes?: string[];
  prerequisites: string[];  // IDs of prerequisite sub-plans
  status: SubPlanStatus;
  transmissionRef?: string;
  partBStatus?: PartBSubPlanStatus;
  partBGitCommitHash?: string;
  partBCompletedAt?: string;
  createdAt?: string;
}

export type SubPlanStatus =
  | 'PENDING'
  | 'GENERATED'
  | 'REVIEWED'
  | 'CONFIRMED'
  | 'TRANSMITTED';

/** Part B 子方案执行状态(对应 Part B SubPlanStatus 枚举,含 SKIPPED) */
export type PartBSubPlanStatus =
  | 'QUEUED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'COMPLETED_WITH_ERRORS'
  | 'FAILED'
  | 'SKIPPED';

// === 修改日志 ===
export interface ChangeLogEntry {
  what: string;
  why: string;
  before: string;
  after: string;
}

// === 审查反馈 ===
export type ReviewFinalStatus =
  | 'PASSED'
  | 'PASSED_WITH_WARNINGS'
  | 'BLOCKED'
  | 'REVIEW_INFRA_ERROR';

export interface ReviewRoundEvidence {
  round: number;
  receivedAt: string;
  rawResponseLength: number;
  rawResponseSha256: string;
  parseStatus: 'SUCCESS' | 'FAILED';
  schemaValidationStatus: 'SUCCESS' | 'FAILED';
  referenceSummaryAvailable: boolean;
  referenceSnapshotId?: string;
  contractSource?: 'EMBEDDED' | 'INFERRED';
  contractSourceHash?: string;
  deterministicErrorCount?: number;
  deterministicWarningCount?: number;
  responseKind?: 'PRIMARY' | 'SCHEMA_RECOVERY';
  diagnostic?: string;
}

export interface ReviewAuditEvidence {
  reviewId: string;
  rulesetVersion: string;
  startedAt: string;
  completedAt: string;
  rounds: ReviewRoundEvidence[];
}

export interface ReviewIssue {
  severity: 'ERROR' | 'WARN';
  rule: string;
  message: string;
}

export interface ReviewResult {
  status?: ReviewFinalStatus;
  passes: boolean;
  issues: ReviewIssue[];
  reviewLog?: ReviewLogEntry[];
  audit?: ReviewAuditEvidence;
}

export interface ReviewLogEntry {
  round: number;
  action: string;
  errorCount: number;
  message: string;
}

// === 项目 ===
export interface PartBExecutionSnapshot {
  receptionId: string;
  overallStatus: string | null;
  subPlanStatuses: Record<string, PartBSubPlanStatus>;
  generatedFiles: GeneratedFileSummary[];
  executionTimeline: ExecutionTimeline | null;
}

export interface Project {
  id: string;
  projectName: string;
  description?: string;
  rawRequirements?: string;
  modules: DraggedModule[];
  status: WorkflowState;
  masterPlan?: MasterPlan;
  subPlans: SubPlan[];
  partBExecution?: PartBExecutionSnapshot;
  /** Legacy project fields retained for persisted snapshot compatibility. */
  transmissionRef?: string;
  partBOverallStatus?: string;
}

// === 流式消息 ===
export interface SSEMessage {
  type: 'progress' | 'content' | 'complete' | 'error';
  stage?: string;
  message?: string;
  chunk?: string;
  tokensUsed?: number;
  error?: string;
}

// === 模块类型定义（面板用） ===
export interface ModuleTypeDef {
  type: ModuleType;
  name: string;
  icon: string;
  color: string;
  defaultConfig: ModuleConfig;
}

export const MODULE_TYPES: ModuleTypeDef[] = [
  {
    type: 'ENTITY',
    name: '数据模型',
    icon: '📦',
    color: '#1890ff',
    defaultConfig: {
      tableName: '',
      moduleName: '',
      fields: [],
      extendBaseEntity: true,
      needVO: true,
      needExcel: false,
    },
  },
  {
    type: 'API',
    name: 'API接口',
    icon: '🔌',
    color: '#52c41a',
    defaultConfig: {
      pathPrefix: '',
      endpoints: [],
      needAuth: true,
      needLog: false,
    },
  },
  {
    type: 'PAGE',
    name: '前端页面',
    icon: '📄',
    color: '#fa8c16',
    defaultConfig: {
      pageType: 'list',
      pageName: '',
      needSearch: true,
      needPagination: true,
    },
  },
  {
    type: 'FLOW',
    name: '工作流',
    icon: '🔄',
    color: '#722ed1',
    defaultConfig: {
      processName: '',
      nodes: [],
      needForm: true,
    },
  },
  {
    type: 'JOB',
    name: '定时任务',
    icon: '⏰',
    color: '#eb2f96',
    defaultConfig: {
      jobName: '',
      cronExpression: '',
      jobHandler: '',
    },
  },
  {
    type: 'FEIGN',
    name: '远程调用',
    icon: '🔗',
    color: '#13c2c2',
    defaultConfig: {
      targetService: '',
      apiPrefix: '',
      methods: [],
    },
  },
  {
    type: 'EXCEL',
    name: 'Excel导入导出',
    icon: '📊',
    color: '#2f54eb',
    defaultConfig: {
      entityName: '',
      needImport: true,
      needExport: true,
      needTemplate: true,
    },
  },
  {
    type: 'CONFIG',
    name: 'Nacos配置',
    icon: '⚙',
    color: '#595959',
    defaultConfig: {
      configType: 'datasource',
      entries: [],
    },
  },
];
