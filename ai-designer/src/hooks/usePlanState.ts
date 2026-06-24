import { usePlanStore } from '../store/planStore';
import type { SubPlanStatus } from '../types/plan';

/**
 * Zustand 状态绑定封装
 *
 * 提供便捷的派生状态和方法。
 */
export function usePlanState() {
  const project = usePlanStore((s) => s.project);
  const canvasModules = usePlanStore((s) => s.canvasModules);
  const isStreaming = usePlanStore((s) => s.isStreaming);
  const streamingContent = usePlanStore((s) => s.streamingContent);
  const reviewResult = usePlanStore((s) => s.reviewResult);

  const setProjectStatus = usePlanStore((s) => s.setProjectStatus);
  const setMasterPlan = usePlanStore((s) => s.setMasterPlan);
  const setSubPlans = usePlanStore((s) => s.setSubPlans);
  const updateSubPlanStatus = usePlanStore((s) => s.updateSubPlanStatus);
  const setReviewResult = usePlanStore((s) => s.setReviewResult);

  /** 当前工作流阶段 */
  const stage = project?.status || 'DRAFT';

  /** 是否可以开始方案生成 */
  const canGenerate = stage === 'DRAFT' || stage === 'ANALYZED';

  /** 是否可以审查方案 */
  const canReview =
    stage === 'PLAN_GENERATED' || stage === 'REVIEWED';

  /** 是否可以拆分方案 */
  const canSplit = stage === 'REVIEWED' || stage === 'PLAN_CONFIRMED';

  /** 是否可以传输 */
  const canTransmit =
    stage === 'SUBPLANS_REVIEWED' || stage === 'SUBPLANS_CONFIRMED';

  /** 获取指定状态的子方案数量 */
  const subPlanCountByStatus = (status: SubPlanStatus) =>
    (project?.subPlans || []).filter((sp) => sp.status === status).length;

  return {
    project,
    canvasModules,
    isStreaming,
    streamingContent,
    reviewResult,
    stage,
    canGenerate,
    canReview,
    canSplit,
    canTransmit,
    setProjectStatus,
    setMasterPlan,
    setSubPlans,
    updateSubPlanStatus,
    setReviewResult,
    subPlanCountByStatus,
  };
}
