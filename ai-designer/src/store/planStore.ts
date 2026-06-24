import { create } from 'zustand';
import type { Project, MasterPlan, SubPlan, DraggedModule, WorkflowState, ReviewResult, SubPlanStatus, PartBSubPlanStatus } from '../types/plan';
import type { GeneratedFileSummary, ExecutionTimeline } from '../types/api';
import type { DemoSeed } from '../demo/orderManagement';

interface PlanStore {
  // 当前项目
  project: Project | null;
  // 画布上的拖拽模块
  canvasModules: DraggedModule[];
  // LLM流式响应缓冲
  streamingContent: string;
  isStreaming: boolean;
  // 审查结果
  reviewResult: ReviewResult | null;
  // Part B 接收编号(由 Part B 在 /api/plans/receive 返回; 后续状态轮询使用)
  receptionId: string | null;
  // Part B执行状态轮询(子方案级别,值与 Part B 上报状态一致)
  partBStatuses: Record<string, PartBSubPlanStatus>;
  // Part B 整体执行状态: RECEIVED / EXECUTING / COMPLETED / FAILED
  partBOverallStatus: string | null;
  // Part B 生成的代码文件(摘要)
  generatedFiles: GeneratedFileSummary[];
  // Part B 执行进度时间线
  executionTimeline: ExecutionTimeline | null;

  // Actions
  createProject: (name: string) => void;
  setProjectStatus: (status: WorkflowState) => void;
  addModuleToCanvas: (module: DraggedModule) => void;
  removeModuleFromCanvas: (id: string) => void;
  updateModuleConfig: (id: string, config: Partial<DraggedModule['config']>) => void;
  setMasterPlan: (plan: MasterPlan) => void;
  setSubPlans: (subPlans: SubPlan[]) => void;
  removeSubPlan: (id: string) => void;
  /** 删除子方案之间的依赖边 (source → target),也支持 ReactFlow 的 edge id 形式 "src->tgt" */
  removeSubPlanDependency: (sourceId: string, targetId: string) => void;
  updateSubPlanStatus: (id: string, status: SubPlanStatus) => void;
  setStreamingContent: (content: string) => void;
  appendStreamingChunk: (chunk: string) => void;
  setIsStreaming: (streaming: boolean) => void;
  setReviewResult: (result: ReviewResult | null) => void;
  setReceptionId: (id: string | null) => void;
  setPartBStatus: (subPlanId: string, status: PartBSubPlanStatus) => void;
  setPartBOverallStatus: (status: string | null) => void;
  setGeneratedFiles: (files: GeneratedFileSummary[]) => void;
  setExecutionTimeline: (timeline: ExecutionTimeline | null) => void;
  /** 一键载入示例数据(模块+主方案+子方案均已 REVIEWED) */
  loadDemo: (seed: DemoSeed) => void;
  resetProject: () => void;
}

let idCounter = 0;
const genId = () => `node_${Date.now()}_${++idCounter}`;

export const usePlanStore = create<PlanStore>((set) => ({
  project: null,
  canvasModules: [],
  streamingContent: '',
  isStreaming: false,
  reviewResult: null,
  receptionId: null,
  partBStatuses: {},
  partBOverallStatus: null,
  generatedFiles: [],
  executionTimeline: null,

  createProject: (name: string) =>
    set({
      project: {
        id: genId(),
        projectName: name,
        modules: [],
        status: 'DRAFT',
        subPlans: [],
      },
      canvasModules: [],
      streamingContent: '',
      isStreaming: false,
      reviewResult: null,
      receptionId: null,
      partBStatuses: {},
      partBOverallStatus: null,
      generatedFiles: [],
    }),

  setProjectStatus: (status: WorkflowState) =>
    set((state) => ({
      project: state.project ? { ...state.project, status } : null,
    })),

  addModuleToCanvas: (module: DraggedModule) =>
    set((state) => {
      const newModule = { ...module, id: `${module.type}_${genId()}` };
      return {
        canvasModules: [...state.canvasModules, newModule],
        project: state.project
          ? { ...state.project, modules: [...state.project.modules, newModule] }
          : null,
      };
    }),

  removeModuleFromCanvas: (id: string) =>
    set((state) => ({
      canvasModules: state.canvasModules.filter((m) => m.id !== id),
      project: state.project
        ? { ...state.project, modules: state.project.modules.filter((m) => m.id !== id) }
        : null,
    })),

  updateModuleConfig: (id: string, config: Partial<DraggedModule['config']>) =>
    set((state) => {
      const updateModule = (m: DraggedModule) =>
        m.id === id ? { ...m, config: { ...m.config, ...config } } : m;
      return {
        canvasModules: state.canvasModules.map(updateModule),
        project: state.project
          ? { ...state.project, modules: state.project.modules.map(updateModule) }
          : null,
      };
    }),

  setMasterPlan: (plan: MasterPlan) =>
    set((state) => ({
      project: state.project ? { ...state.project, masterPlan: plan } : null,
    })),

  setSubPlans: (subPlans: SubPlan[]) =>
    set((state) => ({
      project: state.project ? { ...state.project, subPlans } : null,
    })),

  removeSubPlan: (id: string) =>
    set((state) => {
      if (!state.project) return state;
      const remaining = state.project.subPlans
        .filter((sp) => sp.id !== id)
        // 同步剔除依赖中对被删项的引用,避免画布出现孤儿连线
        .map((sp) => ({
          ...sp,
          prerequisites: sp.prerequisites.filter((p) => p !== id),
        }));
      // 同时清理对应的 Part B 状态
      const { [id]: _removed, ...remainingStatuses } = state.partBStatuses;
      return {
        project: { ...state.project, subPlans: remaining },
        partBStatuses: remainingStatuses,
      };
    }),

  removeSubPlanDependency: (sourceId: string, targetId: string) =>
    set((state) => {
      if (!state.project) return state;
      const updated = state.project.subPlans.map((sp) =>
        sp.id === targetId
          ? { ...sp, prerequisites: sp.prerequisites.filter((p) => p !== sourceId) }
          : sp
      );
      return { project: { ...state.project, subPlans: updated } };
    }),

  updateSubPlanStatus: (id: string, status: SubPlanStatus) =>
    set((state) => ({
      project: state.project
        ? {
            ...state.project,
            subPlans: state.project.subPlans.map((sp) =>
              sp.id === id ? { ...sp, status } : sp
            ),
          }
        : null,
    })),

  setStreamingContent: (content: string) => set({ streamingContent: content }),
  appendStreamingChunk: (chunk: string) =>
    set((state) => ({ streamingContent: state.streamingContent + chunk })),

  setIsStreaming: (streaming: boolean) => set({ isStreaming: streaming }),

  setReviewResult: (result: ReviewResult | null) => set({ reviewResult: result }),

  setReceptionId: (id: string | null) => set({ receptionId: id }),

  setPartBStatus: (subPlanId: string, status: PartBSubPlanStatus) =>
    set((state) => ({
      partBStatuses: { ...state.partBStatuses, [subPlanId]: status },
    })),

  setPartBOverallStatus: (status: string | null) => set({ partBOverallStatus: status }),

  setGeneratedFiles: (files: GeneratedFileSummary[]) => set({ generatedFiles: files }),

  setExecutionTimeline: (timeline: ExecutionTimeline | null) => set({ executionTimeline: timeline }),

  loadDemo: (seed: DemoSeed) =>
    set(() => {
      const projectId = genId();
      const masterPlanId = `mp_${genId()}`;
      // 为模块分配真实 id
      const modules: DraggedModule[] = seed.modules.map((m) => ({
        ...m,
        id: `${m.type}_${genId()}`,
      }));
      // 为子方案分配真实 id,并把依赖中的占位符 __SUBPLAN_N__ 替换成实际 id
      const subPlanIds: string[] = seed.subPlans.map(() => `sub_${genId()}`);
      const subPlans: SubPlan[] = seed.subPlans.map((sp, i) => ({
        ...sp,
        id: subPlanIds[i],
        masterPlanId,
        prerequisites: sp.prerequisites.map((ref) => {
          const m = ref.match(/^__SUBPLAN_(\d+)__$/);
          return m ? subPlanIds[Number(m[1])] : ref;
        }),
      }));
      const masterPlan: MasterPlan = {
        ...seed.masterPlan,
        id: masterPlanId,
        projectId,
      };
      return {
        project: {
          id: projectId,
          projectName: seed.projectName,
          rawRequirements: seed.rawRequirements,
          modules,
          status: 'SUBPLANS_REVIEWED',
          masterPlan,
          subPlans,
        },
        canvasModules: modules,
        streamingContent: '',
        isStreaming: false,
        reviewResult: { passes: true, issues: [] },
        receptionId: null,
        partBStatuses: {},
        partBOverallStatus: null,
        generatedFiles: [],
      };
    }),

  resetProject: () =>
    set({
      project: null,
      canvasModules: [],
      streamingContent: '',
      isStreaming: false,
      reviewResult: null,
      receptionId: null,
      partBStatuses: {},
      partBOverallStatus: null,
      generatedFiles: [],
    }),
}));
