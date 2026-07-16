import React, { useEffect, useRef, useState } from 'react';
import { Tree, Tag, Empty, Typography, Button, Space, Popconfirm, Alert, message, Switch, Tooltip } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { DataNode } from 'antd/es/tree';
import { usePlanStore } from '../../store/planStore';
import { transmitPlan } from '../../services/api';
import { usePartBStatusPoll } from '../../hooks/usePartBStatusPoll';
import type { SubPlan, SubPlanStatus, PartBSubPlanStatus } from '../../types/plan';
import { consumeReviewStream } from '../../services/reviewStream';

const { Text } = Typography;
const EMPTY_SUBPLANS: SubPlan[] = [];

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
  COMPLETED_WITH_ERRORS: { color: 'orange', text: 'Completed with errors' },
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
  const writeTarget = usePlanStore((s) => s.writeTarget);
  const setWriteTarget = usePlanStore((s) => s.setWriteTarget);
  const { startPolling, stopPolling } = usePartBStatusPoll();
  const [activeReviewId, setActiveReviewId] = useState<string | null>(null);
  const [reviewProgress, setReviewProgress] = useState('');
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
          item.status === 'REVIEWED' || item.status === 'CONFIRMED' || item.status === 'TRANSMITTED'
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
      item.status === 'REVIEWED' || item.status === 'CONFIRMED' || item.status === 'TRANSMITTED'
    );
    usePlanStore.getState().setProjectStatus(allReviewed ? 'SUBPLANS_REVIEWED' : 'SUBPLANS_GENERATED');
  };

  const handleReviewSubPlan = async (subPlan: SubPlan) => {
    if (activeReviewId) return;
    reviewAbortRef.current?.abort();
    const controller = new AbortController();
    reviewAbortRef.current = controller;
    const projectId = project?.id;
    setActiveReviewId(subPlan.id);
    setIsStreaming(true);
    setReviewProgress('Starting sub-plan review...');
    setProjectStatus('SUBPLAN_REVIEWING');
    try {
      const response = await fetch('/api/llm/review-plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ planContent: subPlan.reviewedContent || subPlan.planContent, stage: 'subplan' }),
        signal: controller.signal,
      });
      if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
      const reader = response.body.getReader();
      const result = await consumeReviewStream(reader, setReviewProgress).finally(() => {
        void reader.cancel().catch(() => undefined);
      });
      if (!projectId || usePlanStore.getState().project?.id !== projectId) return;
      const passed = result.passes && !result.issues.some((issue) => issue.severity === 'ERROR');
      if (!passed) {
        message.warning(`Sub-plan #${subPlan.index} still has unresolved ERROR issues.`);
        restoreSubPlanStatus();
        return;
      }
      setSubPlanReview(subPlan.id, result.fixedContent, result.changeLog);
      message.success(`Sub-plan #${subPlan.index} reviewed.`);
    } catch (error) {
      const candidate = error as { name?: string };
      if (candidate?.name !== 'AbortError' && projectId && usePlanStore.getState().project?.id === projectId) {
        message.error(`Sub-plan review failed: ${error instanceof Error ? error.message : String(error)}`);
        restoreSubPlanStatus();
      }
    } finally {
      if (reviewAbortRef.current === controller) reviewAbortRef.current = null;
      setActiveReviewId(null);
      setReviewProgress('');
      setIsStreaming(false);
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
              aria-label="删除子方案"
              onClick={(e) => e.stopPropagation()}
              style={{
                border: 'none', background: 'none', cursor: 'pointer',
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
            loading={activeReviewId === sp.id}
            disabled={Boolean(receptionId) || (Boolean(activeReviewId) && activeReviewId !== sp.id)}
            onClick={(event) => {
              event.stopPropagation();
              void handleReviewSubPlan(sp);
            }}
            style={{ padding: '0 2px', height: 18, fontSize: 10 }}
          >
            {sp.status === 'REVIEWED' ? 'Re-review' : 'Review'}
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
        })),
        metadata: {
          sourceService: 'ai-designer',
          generatedBy: 'claude',
          transmittedAt: new Date().toISOString(),
        },
        writeTarget,
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
      message.error('Part B 通信异常,请确认服务已启动');
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
      {activeReviewId && reviewProgress && (
        <Alert type="info" showIcon message={reviewProgress} style={{ marginTop: 8, fontSize: 11 }} />
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
                  子方案已就绪({subPlans.length} 个)。可先审查单个子方案,或直接传输到 Part B 执行生成。
                </span>
              }
              style={{ marginBottom: 8, padding: '4px 10px' }}
            />
          )}
          <Button
            type="primary"
            size="small"
            block
            onClick={handleTransmit}
            disabled={Boolean(activeReviewId) || (Boolean(receptionId) && !['FAILED', 'COMPLETED_WITH_ERRORS'].includes(partBOverallStatus || ''))}
          >
            🚀 传输到 Part B
          </Button>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 6, padding: '0 2px' }}>
            <Tooltip title="开启后,生成的代码直接写入真实 blade_hgsjy 项目(需 Part B 配置 X-Admin-Token,且自动查重,冲突即拒绝)。默认关闭,落隔离区。">
              <span style={{ fontSize: 11 }}>写入真实项目</span>
            </Tooltip>
            <Switch
              size="small"
              checked={writeTarget === 'REAL'}
              onChange={(checked) => setWriteTarget(checked ? 'REAL' : 'ISOLATED')}
            />
          </div>
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
