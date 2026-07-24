import React, { useEffect, useRef, useState } from 'react';
import { Tree, Tag, Empty, Typography, Button, Space, Popconfirm, Alert, Progress, message, Tooltip } from 'antd';
import { CheckCircleOutlined, DeleteOutlined } from '@ant-design/icons';
import type { DataNode } from 'antd/es/tree';
import { usePlanStore } from '../../store/planStore';
import { transmitPlan } from '../../services/api';
import { deriveGenerationIdentity } from '../../services/generationIdentity';
import { usePartBStatusPoll } from '../../hooks/usePartBStatusPoll';
import type { SubPlan, SubPlanStatus, PartBSubPlanStatus } from '../../types/plan';
import {
  getSubPlanReviewReadiness,
  isSuccessfulReviewResult,
  isTransferableSubPlan,
  reviewSubPlanWithRecovery,
  runLimitedConcurrency,
} from '../../services/subPlanReview';

const { Text } = Typography;
const EMPTY_SUBPLANS: SubPlan[] = [];

const REVIEW_CONCURRENCY = 2;
type ReviewExecutionStatus = 'passed' | 'blocked' | 'failed' | 'cancelled';
interface BatchReviewProgress {
  total: number;
  completed: number;
  passed: number;
  blocked: number;
  failed: number;
}

function requestErrorMessage(error: unknown, fallback: string): string {
  if (error && typeof error === 'object') {
    const candidate = error as { message?: unknown; response?: { data?: unknown } };
    const payload = candidate.response?.data;
    if (payload && typeof payload === 'object') {
      const data = payload as Record<string, unknown>;
      if (typeof data.msg === 'string' && data.msg.trim()) return data.msg;
      if (typeof data.error === 'string' && data.error.trim()) return data.error;
    }
    if (typeof candidate.message === 'string' && candidate.message.trim()) return candidate.message;
  }
  return fallback;
}

const STATUS_TAG: Record<SubPlanStatus, { color: string; text: string }> = {
  PENDING: { color: 'default', text: '待生成' },
  GENERATED: { color: 'processing', text: '已生成' },
  REVIEWED: { color: 'success', text: '已审查' },
  CONFIRMED: { color: 'blue', text: '已确认' },
  TRANSMITTED: { color: 'purple', text: '已传输' },
};

const PARTB_TAG: Record<PartBSubPlanStatus, { color: string; text: string }> = {
  QUEUED: { color: 'default', text: '排队中' },
  EXECUTING: { color: 'gold', text: '执行中' },
  COMPLETED: { color: 'green', text: '已完成' },
  COMPLETED_WITH_ERRORS: { color: 'orange', text: '完成但有错误' },
  FAILED: { color: 'red', text: '失败' },
  SKIPPED: { color: 'orange', text: '已跳过' },
};

interface SubPlanNavigatorProps {
  /** 传输成功后跳转到「执行进度」Tab(由 ContextPanel 注入) */
  onSwitchTab?: (key: string) => void;
}

const SubPlanNavigator: React.FC<SubPlanNavigatorProps> = ({ onSwitchTab }) => {
  const project = usePlanStore((s) => s.project);
  const subPlans = usePlanStore((s) => s.project?.subPlans ?? EMPTY_SUBPLANS);
  const setProjectStatus = usePlanStore((s) => s.setProjectStatus);
  const setIsStreaming = usePlanStore((s) => s.setIsStreaming);
  const updateSubPlanStatus = usePlanStore((s) => s.updateSubPlanStatus);
  const setSubPlanReview = usePlanStore((s) => s.setSubPlanReview);
  const setSubPlanReviewDraft = usePlanStore((s) => s.setSubPlanReviewDraft);
  const clearSubPlanReview = usePlanStore((s) => s.clearSubPlanReview);
  const removeSubPlan = usePlanStore((s) => s.removeSubPlan);
  const setPartBStatus = usePlanStore((s) => s.setPartBStatus);
  const setPartBOverallStatus = usePlanStore((s) => s.setPartBOverallStatus);
  const setGeneratedFiles = usePlanStore((s) => s.setGeneratedFiles);
  const setExecutionTimeline = usePlanStore((s) => s.setExecutionTimeline);
  const setReceptionId = usePlanStore((s) => s.setReceptionId);
  const receptionId = usePlanStore((s) => s.receptionId);
  const partBStatuses = usePlanStore((s) => s.partBStatuses);
  const partBOverallStatus = usePlanStore((s) => s.partBOverallStatus);
  const generatedFilesCount = usePlanStore((s) => s.generatedFiles.length);
  const { startPolling, stopPolling } = usePartBStatusPoll();
  const [reviewingIds, setReviewingIds] = useState<string[]>([]);
  const [reviewProgressById, setReviewProgressById] = useState<Record<string, string>>({});
  const [batchReviewProgress, setBatchReviewProgress] = useState<BatchReviewProgress | null>(null);
  const reviewAbortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const projectId = project?.id;
    return () => {
      reviewAbortRef.current?.abort();
      reviewAbortRef.current = null;
      const state = usePlanStore.getState();
      const currentProject = state.project;
      state.setIsStreaming(false);
      if (currentProject && currentProject.id === projectId && currentProject.status === 'SUBPLAN_REVIEWING') {
        const allReviewed = currentProject.subPlans.length > 0 && currentProject.subPlans.every((item) =>
          isTransferableSubPlan(item, currentProject.masterPlan?.contractHash)
        );
        state.setProjectStatus(allReviewed ? 'SUBPLANS_REVIEWED' : 'SUBPLANS_GENERATED');
      }
    };
  }, [project?.id]);

  // 进入终态时给用户一次性提示
  const lastNotifiedStatus = useRef<string | null>(null);
  useEffect(() => {
    if (!partBOverallStatus) return;
    if (lastNotifiedStatus.current === partBOverallStatus) return;
    if (partBOverallStatus === 'COMPLETED') {
      message.success({
        content: `🎉 Part B 已完成所有子方案,共生成 ${generatedFilesCount} 个文件,切到「生成文件」Tab 查看`,
        duration: 6,
      });
      lastNotifiedStatus.current = partBOverallStatus;
    } else if (partBOverallStatus === 'COMPLETED_WITH_ERRORS' || partBOverallStatus === 'FAILED') {
      message.warning({
        content: `Part B 执行结束但部分子方案失败,已生成 ${generatedFilesCount} 个文件供查看`,
        duration: 6,
      });
      lastNotifiedStatus.current = partBOverallStatus;
    }
  }, [partBOverallStatus, generatedFilesCount]);

  const masterContractHash = project?.masterPlan?.contractHash;
  const masterReviewId = project?.masterPlan?.reviewId || project?.masterPlan?.reviewAudit?.reviewId;
  const reviewReadiness = getSubPlanReviewReadiness(subPlans, masterContractHash);
  const reviewInProgress = reviewingIds.length > 0 || batchReviewProgress !== null;
  const masterReviewReady = Boolean(
    masterReviewId?.trim()
    && (project?.masterPlan?.reviewStatus === 'PASSED' || project?.masterPlan?.reviewStatus === 'PASSED_WITH_WARNINGS')
    && masterContractHash?.trim(),
  );
  const canTransmit = masterReviewReady && reviewReadiness.canTransmit;
  const transmitBlockReason = !masterReviewReady
    ? '主方案缺少当前有效的审核凭证'
    : reviewReadiness.pending > 0
      ? `还有 ${reviewReadiness.pending} 个子方案缺少当前有效审核凭证`
      : '所有审核凭证均有效';

  if (subPlans.length === 0) {
    return (
      <div style={{ padding: 16 }}>
        <Empty description="尚未拆分子方案" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        <Text type="secondary" style={{ fontSize: 11, display: 'block', textAlign: 'center' }}>
          确认总方案后可拆分为多个子方案
        </Text>
      </div>
    );
  }

  const restoreSubPlanStatus = () => {
    const current = usePlanStore.getState().project;
    if (!current) return;
    const allReviewed = current.subPlans.length > 0 && current.subPlans.every((item) =>
      isTransferableSubPlan(item, current.masterPlan?.contractHash)
    );
    usePlanStore.getState().setProjectStatus(allReviewed ? 'SUBPLANS_REVIEWED' : 'SUBPLANS_GENERATED');
  };

  const setReviewing = (subPlanId: string, active: boolean) => {
    setReviewingIds((current) => active
      ? current.includes(subPlanId) ? current : [...current, subPlanId]
      : current.filter((id) => id !== subPlanId));
  };

  const executeSubPlanReview = async (
    subPlan: SubPlan,
    controller: AbortController,
    notify: boolean,
  ): Promise<ReviewExecutionStatus> => {
    const projectId = project?.id;
    if (!projectId || !masterReviewId) {
      if (notify) message.error('请先完成主方案审核，再审核子方案。');
      return 'failed';
    }
    clearSubPlanReview(subPlan.id);
    setProjectStatus('SUBPLAN_REVIEWING');
    setReviewing(subPlan.id, true);
    setReviewProgressById((current) => ({ ...current, [subPlan.id]: '正在准备子方案审核...' }));
    try {
      const outcome = await reviewSubPlanWithRecovery(
        projectId,
        masterReviewId,
        subPlan,
        controller.signal,
        (event) => setReviewProgressById((current) => ({ ...current, [subPlan.id]: event.message })),
      );
      if (usePlanStore.getState().project?.id !== projectId) return 'cancelled';
      const { result, attempts } = outcome;
      const expectedContractHash = usePlanStore.getState().project?.masterPlan?.contractHash;
      if (!expectedContractHash || result.contractHash !== expectedContractHash) {
        if (notify) message.error(`子方案 #${subPlan.index} 的审核结果改变了统一契约，已拒绝接受。`);
        return 'failed';
      }
      if (!isSuccessfulReviewResult(result)) {
        setSubPlanReviewDraft(subPlan.id, result.fixedContent, result.changeLog, result.contractHash);
        if (notify) {
          message.warning(`子方案 #${subPlan.index} 经过 ${attempts} 次审核仍被阻断，已保留修复后草案。`);
        }
        return 'blocked';
      }
      setSubPlanReview(subPlan.id, result.fixedContent, result.changeLog, {
        reviewId: result.reviewId,
        contractHash: result.contractHash,
        reviewStatus: result.status,
        reviewAudit: result.audit,
      });
      if (notify) message.success(`子方案 #${subPlan.index} 审核通过${attempts > 1 ? '（自动修复后复审通过）' : ''}。`);
      return 'passed';
    } catch (error) {
      const candidate = error as { name?: string };
      if (candidate?.name === 'AbortError') return 'cancelled';
      if (projectId && usePlanStore.getState().project?.id === projectId && notify) {
        message.error(`子方案审核失败：${requestErrorMessage(error, '未知审核错误')}`);
      }
      return 'failed';
    } finally {
      setReviewing(subPlan.id, false);
      setReviewProgressById((current) => {
        const { [subPlan.id]: _removed, ...remaining } = current;
        return remaining;
      });
    }
  };

  const handleReviewSubPlan = async (subPlan: SubPlan) => {
    if (!masterReviewReady || reviewInProgress || reviewAbortRef.current || receptionId) return;
    const controller = new AbortController();
    reviewAbortRef.current = controller;
    setIsStreaming(true);
    setProjectStatus('SUBPLAN_REVIEWING');
    try {
      await executeSubPlanReview(subPlan, controller, true);
    } finally {
      if (reviewAbortRef.current === controller) reviewAbortRef.current = null;
      setIsStreaming(false);
      restoreSubPlanStatus();
    }
  };

  const handleReviewAll = async () => {
    if (!project || !masterReviewReady || reviewInProgress || reviewAbortRef.current || receptionId) return;
    const pending = project.subPlans.filter((item) => !isTransferableSubPlan(item, project.masterPlan?.contractHash));
    if (pending.length === 0) {
      message.success('所有子方案都已拥有当前有效的审核凭证。');
      return;
    }
    const controller = new AbortController();
    reviewAbortRef.current = controller;
    setIsStreaming(true);
    setProjectStatus('SUBPLAN_REVIEWING');
    setBatchReviewProgress({ total: pending.length, completed: 0, passed: 0, blocked: 0, failed: 0 });
    try {
      const settled = await runLimitedConcurrency(
        pending,
        REVIEW_CONCURRENCY,
        (subPlan) => executeSubPlanReview(subPlan, controller, false),
        (result) => {
          const status = result.status === 'fulfilled' ? result.value : 'failed';
          setBatchReviewProgress((current) => current ? {
            ...current,
            completed: current.completed + 1,
            passed: current.passed + (status === 'passed' ? 1 : 0),
            blocked: current.blocked + (status === 'blocked' ? 1 : 0),
            failed: current.failed + (status === 'failed' ? 1 : 0),
          } : current);
        },
      );
      if (usePlanStore.getState().project?.id !== project.id) return;
      const statuses = settled.map((item) => item.status === 'fulfilled' ? item.value : 'failed');
      const passed = statuses.filter((status) => status === 'passed').length;
      const blocked = statuses.filter((status) => status === 'blocked').length;
      const failed = statuses.filter((status) => status === 'failed').length;
      if (blocked === 0 && failed === 0) {
        message.success(`本次 ${passed} 个待审核子方案已全部通过。`);
      } else {
        const blockedLabels = pending.filter((_, index) => statuses[index] === 'blocked').map((item) => `#${item.index}`).join('、');
        const failedLabels = pending.filter((_, index) => statuses[index] === 'failed').map((item) => `#${item.index}`).join('、');
        const details = [
          blockedLabels ? `阻断：${blockedLabels}` : '',
          failedLabels ? `失败：${failedLabels}` : '',
        ].filter(Boolean).join('；');
        message.warning(`批量审核完成：${passed} 个通过，${blocked} 个阻断，${failed} 个失败${details ? `（${details}）` : ''}。已保留成功结果。`);
      }
    } finally {
      if (reviewAbortRef.current === controller) reviewAbortRef.current = null;
      setBatchReviewProgress(null);
      setIsStreaming(false);
      restoreSubPlanStatus();
    }
  };

  const treeData: DataNode[] = subPlans.map((sp) => {
    const tag = STATUS_TAG[sp.status] || STATUS_TAG.PENDING;
    const pbStatus = partBStatuses[sp.id];
    const pbTag = pbStatus ? PARTB_TAG[pbStatus] : null;
    return {
      key: sp.id,
      title: (
        <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, flexWrap: 'nowrap' }}>
          <Popconfirm
            disabled={reviewInProgress || Boolean(receptionId)}
            title="移除该子方案?"
            okText="移除"
            cancelText="取消"
            onConfirm={(e) => {
              if (e) e.stopPropagation();
              removeSubPlan(sp.id);
            }}
          >
            <button
              type="button"
              disabled={reviewInProgress || Boolean(receptionId)}
              aria-label="删除子方案"
              onClick={(e) => e.stopPropagation()}
              style={{
                border: 'none', background: 'none', cursor: reviewInProgress || receptionId ? 'not-allowed' : 'pointer',
                padding: '0 2px 0 0', color: '#999', fontSize: 11, lineHeight: 1,
              }}
            >
              <DeleteOutlined />
            </button>
          </Popconfirm>
          <span style={{ fontWeight: 500, whiteSpace: 'nowrap' }}>#{sp.index} {sp.title}</span>
          <Tag color={tag.color} style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>
            {tag.text}
          </Tag>
          <Button
            type="link"
            size="small"
            loading={reviewingIds.includes(sp.id)}
            disabled={!masterReviewReady || Boolean(receptionId) || reviewInProgress}
            onClick={(event) => {
              event.stopPropagation();
              void handleReviewSubPlan(sp);
            }}
            style={{ padding: '0 2px', height: 18, fontSize: 10 }}
          >
            {isTransferableSubPlan(sp, masterContractHash) ? '重新审核' : '审核'}
          </Button>
          {pbTag && (
            <Tag color={pbTag.color} style={{ fontSize: 10, lineHeight: '16px', margin: 0 }}>
              B: {pbTag.text}
            </Tag>
          )}
        </div>
      ),
      children: sp.prerequisites?.length
        ? [
            {
              key: `${sp.id}-deps`,
              title: (
                <Text type="secondary" style={{ fontSize: 11 }}>
                  依赖: {sp.prerequisites.map((p) => {
                    const prereq = subPlans.find((s) => s.id === p);
                    return prereq ? `#${prereq.index} ${prereq.title}` : p;
                  }).join(', ')}
                </Text>
              ),
            },
          ]
        : [],
    };
  });

  const handleTransmit = async () => {
    if (!project?.masterPlan || subPlans.length === 0) {
      message.error('Master plan or sub-plans are missing.');
      return;
    }
    if (!masterReviewReady) {
      message.error('主方案缺少当前有效的审核凭证。');
      return;
    }
    if (!reviewReadiness.canTransmit) {
      message.error(`还有 ${reviewReadiness.pending} 个子方案需要成功审核。`);
      return;
    }
    const emptyPlan = subPlans.find((item) => !(item.reviewedContent || item.planContent).trim());
    if (emptyPlan) {
      message.error(`Sub-plan #${emptyPlan.index} has no content.`);
      return;
    }
    const ids = new Set(subPlans.map((item) => item.id));
    const orphan = subPlans.find((item) => item.prerequisites.some((id) => !ids.has(id)));
    if (orphan) {
      message.error(`Sub-plan #${orphan.index} contains an invalid prerequisite.`);
      return;
    }

    stopPolling();
    setPartBOverallStatus(null);
    setGeneratedFiles([]);
    setExecutionTimeline(null);
    setProjectStatus('TRANSMITTING');

    try {
      const result = await transmitPlan({
        projectId: project.id,
        projectName: project.projectName,
        masterPlan: {
          id: project.masterPlan.id,
          version: project.masterPlan.version,
          content: project.masterPlan.reviewedContent || project.masterPlan.planContent,
        },
        subPlans: subPlans.map((sp) => ({
          id: sp.id,
          index: sp.index,
          title: sp.title,
          content: sp.reviewedContent || sp.planContent,
          prerequisites: sp.prerequisites,
          deliverableIds: sp.deliverableIds || [],
          contractHash: sp.contractHash || project.masterPlan?.contractHash || '',
          referencedElementIds: sp.referencedElementIds,
          inputTypes: sp.inputTypes,
          outputTypes: sp.outputTypes,
        })),
        metadata: {
          sourceService: 'ai-designer',
          generatedBy: 'claude',
          transmittedAt: new Date().toISOString(),
        },
        generationIdentity: deriveGenerationIdentity(project),
        reviewManifest: {
          masterReviewId: project.masterPlan.reviewId || project.masterPlan.reviewAudit?.reviewId || '',
          subPlanReviews: subPlans.map((sp) => ({ subPlanId: sp.id, reviewId: sp.reviewId || sp.reviewAudit?.reviewId || '' })),
        },
        writeTarget: 'ISOLATED',
      });

      const transmissionData = result.data;
      if (result.success && transmissionData?.receptionId) {
        subPlans.forEach((sp) => {
          updateSubPlanStatus(sp.id, 'TRANSMITTED');
          // 优先采用 Part B 上报的初始状态,缺失则按 QUEUED 兜底
          const initial = (transmissionData.subPlanStatuses?.[sp.id] as PartBSubPlanStatus) || 'QUEUED';
          setPartBStatus(sp.id, initial);
        });
        setReceptionId(transmissionData.receptionId);
        setProjectStatus('TRANSMITTED');
        message.success(`方案已传输 (receptionId=${transmissionData.receptionId}),Part B 开始执行`);
        // 不再需要手动 startPolling — usePartBStatusPoll 的 useEffect 会在
        // receptionId 变化时自动启动轮询,杜绝闭包/竞态。
        // 传输成功后自动跳转到「执行进度」Tab,让用户看到 Part B 的实时执行
        onSwitchTab?.('progress');
      } else {
        message.error(result.msg || '传输失败');
        setProjectStatus('SUBPLANS_CONFIRMED');
      }
    } catch (err) {
      console.error('传输失败:', err);
      message.error(requestErrorMessage(err, 'Part B 通信失败，请检查服务健康状态。'));
      setProjectStatus('SUBPLANS_CONFIRMED');
    }
  };

  return (
    <div style={{ padding: '0 12px' }}>
      <Tree
        treeData={treeData}
        defaultExpandAll
        showLine
        style={{ fontSize: 12 }}
      />
      {reviewInProgress && (
        <Alert
          type="info"
          showIcon
          message={batchReviewProgress
            ? (
              <div>
                <div>批量审核：已完成 {batchReviewProgress.completed}/{batchReviewProgress.total}，通过 {batchReviewProgress.passed}，阻断 {batchReviewProgress.blocked}，失败 {batchReviewProgress.failed}</div>
                <Progress
                  percent={Math.round(batchReviewProgress.completed / Math.max(1, batchReviewProgress.total) * 100)}
                  size="small"
                  status={batchReviewProgress.failed > 0 ? 'exception' : 'active'}
                  style={{ marginTop: 4, marginBottom: -6 }}
                />
              </div>
            )
            : reviewProgressById[reviewingIds[0]] || '正在审核子方案...'}
          description="审核连接中断时会自动切换为持久化状态恢复；同一子方案不会重复启动相同审核。"
          style={{ marginTop: 8, fontSize: 11 }}
        />
      )}
      {(project?.status === 'SUBPLANS_GENERATED' ||
        project?.status === 'SUBPLAN_REVIEWING' ||
        project?.status === 'SUBPLANS_REVIEWED' ||
        project?.status === 'SUBPLANS_CONFIRMED' ||
        project?.status === 'TRANSMITTED') && (
        <div style={{ marginTop: 12 }}>
          {!receptionId && (
            <Alert
              type="info"
              showIcon
              message={
                <span style={{ fontSize: 11 }}>
                  子方案审核进度：{reviewReadiness.reviewed}/{reviewReadiness.total}。传输前必须全部审核通过；可逐个审核或使用下方“一键审核全部”。
                </span>
              }
              style={{ marginBottom: 8, padding: '4px 10px' }}
            />
          )}
          <Tooltip title={!masterReviewReady ? '请先完成主方案审核' : reviewReadiness.pending === 0 ? '所有子方案已审核' : ''}>
            <span style={{ display: 'block' }}>
              <Button
                size="small"
                block
                icon={<CheckCircleOutlined />}
                loading={batchReviewProgress !== null}
                disabled={!masterReviewReady || Boolean(receptionId) || reviewInProgress || reviewReadiness.pending === 0}
                onClick={() => void handleReviewAll()}
                style={{ marginBottom: 8 }}
              >
                {reviewReadiness.pending > 0 ? `一键审核全部（剩余 ${reviewReadiness.pending} 个）` : '全部子方案已审核'}
              </Button>
            </span>
          </Tooltip>
          <Tooltip title={transmitBlockReason}>
            <span style={{ display: 'block' }}>
              <Button
                type="primary"
                size="small"
                block
                onClick={handleTransmit}
                disabled={!canTransmit || reviewInProgress || (Boolean(receptionId) && !['FAILED', 'COMPLETED_WITH_ERRORS'].includes(partBOverallStatus || ''))}
              >
                🚀 传输到 Part B
              </Button>
            </span>
          </Tooltip>
        </div>
      )}
      {receptionId && (
        <div style={{ marginTop: 12, padding: 8, background: '#fafafa', borderRadius: 4 }}>
          <Text style={{ fontSize: 11, display: 'block' }}>
            <Text strong>Part B receptionId:</Text> <Text code>{receptionId}</Text>
          </Text>
          {partBOverallStatus && (
            <Text style={{ fontSize: 11, display: 'block', marginTop: 4 }}>
              <Text strong>整体状态:</Text>{' '}
              <Tag color={
                partBOverallStatus === 'COMPLETED' ? 'green'
                  : partBOverallStatus === 'COMPLETED_WITH_ERRORS' ? 'orange'
                  : partBOverallStatus === 'FAILED' ? 'red'
                  : partBOverallStatus === 'EXECUTING' ? 'gold' : 'blue'
              }>
                {partBOverallStatus}
              </Tag>
            </Text>
          )}
          <Space size={4} style={{ marginTop: 6 }}>
            <Button size="small" onClick={startPolling}>刷新</Button>
            <Button size="small" onClick={stopPolling}>停止轮询</Button>
          </Space>
        </div>
      )}
    </div>
  );
};

export default SubPlanNavigator;
