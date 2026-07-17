// === API 请求类型 ===

import type { DraggedModule } from './plan';

/** 发送到 Part B 的方案传输请求 */
export interface GenerationIdentityPayload {
  moduleName: string;
  entityName: string;
  tableName: string;
  basePackage: string;
  apiModuleName: string;
  serviceModuleName: string;
  serviceName: string;
}

export interface PlanTransmitRequest {
  projectId: string;
  projectName: string;
  masterPlan: {
    id: string;
    version: number;
    content: string;
  };
  subPlans: {
    id: string;
    index: number;
    title: string;
    content: string;
    prerequisites: string[];
  }[];
  metadata: {
    sourceService: string;
    generatedBy: string;
    transmittedAt: string;
  };
  generationIdentity: GenerationIdentityPayload;
  /** 写入目标: 'ISOLATED'(默认,隔离区) | 'REAL'(真实 blade_hgsjy,需鉴权+查重)。可选,不传按 ISOLATED */
  writeTarget?: 'ISOLATED' | 'REAL';
}

/** Part B 响应 */
export interface PlanTransmitResponse {
  code: number;
  success: boolean;
  data?: {
    receptionId: string;
    status: string;
    subPlanStatuses: Record<string, string>;
  };
  msg?: string;
}

/** 状态回调 */
export interface StatusUpdateRequest {
  receptionId: string;
  projectId: string;
  overallStatus: string;
  subPlanUpdates: {
    subPlanId: string;
    status: string;
    gitCommitHash?: string;
    completedAt?: string;
    startedAt?: string;
  }[];
}

/** LLM 生成方案请求 */
export interface GeneratePlanRequest {
  userInput: string;
  modules: DraggedModule[];
  projectId?: string;
}

/** LLM 审查方案请求 */
export interface ReviewPlanRequest {
  planContent: string;
  stage: 'master' | 'subplan';
}

/** LLM 拆分子方案请求 */
export interface SplitPlanRequest {
  planContent: string;
}

/** LLM 响应 */
export interface LLMResponse {
  success: boolean;
  data?: unknown;
  error?: string;
  tokensUsed?: number;
}

/** 生成文件摘要 */
export interface GeneratedFileSummary {
  id: number;
  subPlanId: number;
  partASubPlanId?: string;
  subPlanTitle?: string;
  fileType?: string;
  filePath: string;
  fileName: string;
  fileExtension?: string;
  action?: string;
  sizeBytes?: number;
  lineCount?: number;
  createTime?: string;
}

/** 生成文件详情 */
export interface GeneratedFileDetail extends GeneratedFileSummary {
  content: string;
}

/** 执行进度时间线 — 单步 */
export interface TimelineStep {
  id: number;
  stage: string;
  status: string;
  action?: string;
  filePath?: string;
  reason?: string;
  createTime?: string;
}

/** 单个子方案的时间线 */
export interface SubPlanTimeline {
  subPlanId: number;
  partASubPlanId?: string;
  index?: number;
  title?: string;
  status?: string;
  errorMessage?: string;
  fileCount: number;
  startedAt?: string;
  completedAt?: string;
  steps: TimelineStep[];
}

/** 整个方案的时间线 */
export interface ExecutionTimeline {
  receptionId: string;
  overallStatus?: string;
  totalSubPlans: number;
  completedSubPlans: number;
  failedSubPlans: number;
  moduleName?: string;
  entityName?: string;
  tableName?: string;
  basePackage?: string;
  frameworkVersion?: string;
  javaVersion?: string;
  outputDirectory?: string;
  compileVerificationStatus?: 'NOT_RUN' | 'PASSED' | 'FAILED' | 'SKIPPED_DEPENDENCIES_UNAVAILABLE';
  qualityErrorCount?: number;
  qualityWarningCount?: number;
  subPlanTimelines: SubPlanTimeline[];
}
