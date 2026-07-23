import React, { useEffect, useRef, useState } from 'react';
import { List, Tag, Empty, Typography, Button, Space, Alert, Progress, message } from 'antd';
import {
  CheckCircleOutlined,
  WarningOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import { splitPlan, SplitPlanError } from '../../services/api';
import { ReviewStreamError, type ReviewStreamResult } from '../../services/reviewStream';
import { consumeReviewResponseWithRecovery, fetchLatestReviewStatus, fetchReviewStatus, mergeReviewProgressEvent, waitForPersistedReview } from '../../services/reviewStatus';
import type { ReviewProgressEvent } from '../../services/reviewStream';

const { Text, Paragraph } = Typography;

/**
 * 当前进行中的操作类型,用于在按钮上显示 loading + 文案。
 * - 'reviewing' 审查总方案
 * - 'splitting' 拆分子方案
 * - null 空闲
 */
type ActiveOp = 'reviewing' | 'splitting' | null;

function isCancellationError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const candidate = error as { name?: unknown; code?: unknown };
  return candidate.name === 'AbortError'
    || candidate.name === 'CanceledError'
    || candidate.code === 'ERR_CANCELED'
    || candidate.code === 'REVIEW_CANCELLED';
}

interface ReviewFeedbackProps {
  /** 跳转到指定 Tab(由 ContextPanel 注入,用于拆分完成后自动切到子方案 Tab) */
  onSwitchTab?: (key: string) => void;
}

const ReviewFeedback: React.FC<ReviewFeedbackProps> = ({ onSwitchTab }) => {
  const reviewResult = usePlanStore((s) => s.reviewResult);
  const setReviewResult = usePlanStore((s) => s.setReviewResult);
  const project = usePlanStore((s) => s.project);
  const setProjectStatus = usePlanStore((s) => s.setProjectStatus);
  const setMasterPlan = usePlanStore((s) => s.setMasterPlan);
  const setSubPlans = usePlanStore((s) => s.setSubPlans);
  const setIsStreaming = usePlanStore((s) => s.setIsStreaming);
  const [activeOp, setActiveOp] = useState<ActiveOp>(null);
  const [reviewProgressEvents, setReviewProgressEvents] = useState<ReviewProgressEvent[]>([]);
  const [reviewProgressOutcome, setReviewProgressOutcome] = useState<'idle' | 'running' | 'success' | 'warning' | 'error'>('idle');
  const [currentReviewId, setCurrentReviewId] = useState<string | null>(null);
  const [lastReviewActivityAt, setLastReviewActivityAt] = useState<number | null>(null);
  const [reviewStartedAtMs, setReviewStartedAtMs] = useState<number | null>(null);
  const [, setReviewClock] = useState(0);
  const operationAbortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const projectId = project?.id;
    return () => {
      operationAbortRef.current?.abort();
      operationAbortRef.current = null;
      const state = usePlanStore.getState();
      state.setIsStreaming(false);
      const current = state.project;
      if (!current || current.id !== projectId) return;
      if (current.status === 'SPLITTING') state.setProjectStatus('REVIEWED');
    };
  }, [project?.id]);

  useEffect(() => {
    if (activeOp !== 'reviewing') return;
    const timer = window.setInterval(() => setReviewClock((value) => value + 1), 1_000);
    return () => window.clearInterval(timer);
  }, [activeOp]);

  const restoreCancelledOperation = (operation: ActiveOp, projectId: string) => {
    const state = usePlanStore.getState();
    if (state.project?.id !== projectId) return;
    if (operation === 'reviewing' && state.project.status === 'REVIEWING') {
      state.setProjectStatus('PLAN_GENERATED');
    } else if (operation === 'splitting' && state.project.status === 'SPLITTING') {
      state.setProjectStatus('REVIEWED');
    }
  };

  const cancelActiveOperation = () => {
    if (!activeOp || !project) return;
    restoreCancelledOperation(activeOp, project.id);
    const reviewId = currentReviewId || project.masterPlan?.activeReviewId;
    if (activeOp === 'reviewing' && reviewId) {
      void fetch(`/api/llm/review-status/${encodeURIComponent(reviewId)}/cancel`, { method: 'POST' }).catch(() => undefined);
    }
    operationAbortRef.current?.abort();
    if (activeOp === 'reviewing') {
      setReviewProgressOutcome('warning');
      setReviewProgressEvents((current) => mergeReviewProgressEvent(current, {
        stage: 'complete' as const,
        message: '\u5ba1\u67e5\u5df2\u53d6\u6d88\uff0c\u53ef\u91cd\u65b0\u53d1\u8d77\u3002',
      }));
    }
  };

  const applyMasterReviewResult = (
    result: ReviewStreamResult,
    projectId: string,
    masterPlanId: string,
    reviewSourceContent: string,
  ): boolean => {
    const current = usePlanStore.getState().project;
    if (!current || current.id !== projectId || current.masterPlan?.id !== masterPlanId
      || current.masterPlan.planContent !== reviewSourceContent) return false;
    setReviewResult({ status: result.status, passes: result.passes, issues: result.issues,
      reviewLog: result.reviewLog, audit: result.audit });
    const reviewPassed = (result.status === 'PASSED' || result.status === 'PASSED_WITH_WARNINGS')
      && result.passes && !result.issues.some((issue) => issue.severity === 'ERROR');
    setMasterPlan({
      ...current.masterPlan,
      planContent: reviewSourceContent,
      reviewedContent: result.fixedContent,
      reviewChangeLog: result.changeLog,
      reviewStatus: result.status,
      reviewAudit: result.audit,
      reviewId: result.reviewId,
      activeReviewId: undefined,
      contractHash: result.contractHash,
      status: reviewPassed ? 'REVIEWED' : 'PLAN_GENERATED',
    });
    setReviewProgressOutcome(reviewPassed ? 'success' : 'warning');
    setProjectStatus(reviewPassed ? 'REVIEWED' : 'PLAN_GENERATED');
    setCurrentReviewId(null);
    if (reviewPassed) message.success(result.cacheHit ? '已复用相同内容的审核结果' : '审查完成');
    else message.warning(result.cacheHit
      ? '当前内容与参考快照未变化，已复用上次阻断结果；请修改方案后再审核。'
      : '审查完成但仍有阻断问题，请修改后再拆分。');
    return true;
  };

  const handleReviewMasterPlan = async () => {
    if (!project?.masterPlan || activeOp) return;
    setActiveOp('reviewing');
    setIsStreaming(true);
    setProjectStatus('REVIEWING');
    setReviewProgressOutcome('running');
    setReviewStartedAtMs(Date.now());
    setLastReviewActivityAt(Date.now());
    setCurrentReviewId(null);
    setReviewProgressEvents([{
      stage: 'preparing',
      round: 1,
      totalRounds: 1,
      message: '正在建立审查任务...',
    }]);
    setReviewResult(null);
    const reviewSourceContent = project.masterPlan.reviewedContent || project.masterPlan.planContent;
    setMasterPlan({
      ...project.masterPlan,
      planContent: reviewSourceContent,
      reviewedContent: undefined,
      reviewChangeLog: undefined,
      reviewStatus: undefined,
      reviewAudit: undefined,
      reviewId: undefined,
      activeReviewId: undefined,
      contractHash: undefined,
      status: 'PLAN_GENERATED',
    });
    operationAbortRef.current?.abort();
    const controller = new AbortController();
    operationAbortRef.current = controller;
    const projectId = project.id;
    const masterPlanId = project.masterPlan.id;
    const masterPlanVersion = project.masterPlan.version;
    const isCurrentReviewSubject = () => {
      const current = usePlanStore.getState().project;
      return current?.id === projectId && current.masterPlan?.id === masterPlanId
        && current.masterPlan.version === masterPlanVersion
        && current.masterPlan.planContent === reviewSourceContent;
    };
    try {
      const resp = await fetch('/api/llm/review-plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ planContent: reviewSourceContent, stage: 'master', projectId: project.id, subjectId: project.masterPlan.id }),
        signal: controller.signal,
      });
      const rememberReviewId = (reviewId: string) => {
        setCurrentReviewId(reviewId);
        const current = usePlanStore.getState().project;
        if (current?.id === projectId && current.masterPlan?.id === masterPlanId) {
          setMasterPlan({ ...current.masterPlan, activeReviewId: reviewId });
        }
      };
      const result = await consumeReviewResponseWithRecovery(resp, controller.signal, (event) => {
        setLastReviewActivityAt(Date.now());
        setReviewProgressEvents((current) => mergeReviewProgressEvent(current, event));
      }, rememberReviewId);
      if (!isCurrentReviewSubject()) return;
      applyMasterReviewResult(result, projectId, masterPlanId, reviewSourceContent);
    } catch (err) {
      if (isCancellationError(err)) {
        restoreCancelledOperation('reviewing', projectId);
        return;
      }
      if (!isCurrentReviewSubject()) return;
      const reviewError = err instanceof ReviewStreamError ? err : undefined;
      const msg = err instanceof Error ? err.message : String(err);
      const audit = reviewError?.audit;
      console.error('Review failed:', err);
      setReviewResult({
        status: 'REVIEW_INFRA_ERROR',
        passes: false,
        issues: [{ severity: 'ERROR', rule: 'REVIEW-INFRA', message: msg }],
        audit,
      });
      const current = usePlanStore.getState().project;
      if (current?.id === projectId && current.masterPlan?.id === masterPlanId) {
        setMasterPlan({
          ...current.masterPlan,
          planContent: reviewSourceContent,
          reviewedContent: undefined,
          reviewChangeLog: [],
          reviewId: undefined,
          activeReviewId: undefined,
          contractHash: undefined,
          status: 'PLAN_GENERATED',
          reviewStatus: 'REVIEW_INFRA_ERROR',
          reviewAudit: audit,
        });
      }
      setReviewProgressOutcome('error');
      setReviewProgressEvents((current) => mergeReviewProgressEvent(current, {
        stage: 'complete' as const,
        message: `Review infrastructure failed: ${msg}`,
      }));
      message.error(`Review failed and was not allowed to pass: ${msg}`);
      setProjectStatus('PLAN_GENERATED');
    } finally {
      if (operationAbortRef.current === controller) operationAbortRef.current = null;
      setActiveOp(null);
      setCurrentReviewId(null);
      setIsStreaming(false);
    }
  };

  useEffect(() => {
    if (project?.status !== 'REVIEWING' || !project.masterPlan || operationAbortRef.current) return;
    const projectId = project.id;
    const masterPlanId = project.masterPlan.id;
    const reviewSourceContent = project.masterPlan.planContent;
    const controller = new AbortController();
    operationAbortRef.current = controller;
    setActiveOp('reviewing');
    setIsStreaming(true);
    setReviewProgressOutcome('running');
    setReviewProgressEvents([{ stage: 'preparing', round: 1, totalRounds: 1, message: '正在恢复持久化审核任务...' }]);
    setReviewStartedAtMs(Date.now());
    void (async () => {
      try {
        const activeReviewId = usePlanStore.getState().project?.masterPlan?.activeReviewId;
        const status = activeReviewId
          ? await fetchReviewStatus(activeReviewId, controller.signal)
          : await fetchLatestReviewStatus(projectId, masterPlanId, 'master', controller.signal);
        setCurrentReviewId(status.reviewId);
        setReviewStartedAtMs(Date.parse(status.startedAt) || Date.now());
        setLastReviewActivityAt(Date.parse(status.progress?.lastHeartbeatAt || status.updatedAt) || Date.now());
        const current = usePlanStore.getState().project;
        if (current?.id === projectId && current.masterPlan?.id === masterPlanId) {
          setMasterPlan({ ...current.masterPlan, activeReviewId: status.reviewId });
        }
        const result = status.result ?? await waitForPersistedReview(status.reviewId, controller.signal, (event) => {
          setLastReviewActivityAt(Date.now());
          setReviewProgressEvents((events) => mergeReviewProgressEvent(events, event));
        });
        applyMasterReviewResult(result, projectId, masterPlanId, reviewSourceContent);
      } catch (error) {
        if (isCancellationError(error)) return;
        const msg = error instanceof Error ? error.message : String(error);
        setReviewProgressOutcome('error');
        setReviewResult({ status: 'REVIEW_INFRA_ERROR', passes: false,
          issues: [{ severity: 'ERROR', rule: 'REVIEW-RECOVERY', message: msg }] });
        setReviewProgressEvents((events) => mergeReviewProgressEvent(events, { stage: 'complete' as const, message: `审核状态恢复失败：${msg}` }));
        const current = usePlanStore.getState().project;
        if (current?.id === projectId && current.masterPlan?.id === masterPlanId) {
          setMasterPlan({ ...current.masterPlan, activeReviewId: undefined, reviewStatus: 'REVIEW_INFRA_ERROR', status: 'PLAN_GENERATED' });
          setProjectStatus('PLAN_GENERATED');
        }
      } finally {
        if (operationAbortRef.current === controller) operationAbortRef.current = null;
        setActiveOp(null);
        setCurrentReviewId(null);
        setIsStreaming(false);
      }
    })();
    return () => controller.abort();
  }, [project?.id, project?.status, project?.masterPlan?.id]);

  const handleSplitPlan = async () => {
    if (!project?.masterPlan || activeOp) return;
    setActiveOp('splitting');
    setIsStreaming(true);
    setProjectStatus('SPLITTING');
    operationAbortRef.current?.abort();
    const controller = new AbortController();
    operationAbortRef.current = controller;
    const projectId = project.id;
    const masterPlanId = project.masterPlan.id;
    const masterPlanVersion = project.masterPlan.version;
    const reviewedSource = project.masterPlan.reviewedContent || project.masterPlan.planContent;
    const isCurrentSplitSubject = () => {
      const current = usePlanStore.getState().project;
      return current?.id === projectId && current.masterPlan?.id === masterPlanId
        && current.masterPlan.version === masterPlanVersion
        && (current.masterPlan.reviewedContent || current.masterPlan.planContent) === reviewedSource;
    };
    try {
      const result = await splitPlan(
        reviewedSource,
        project.masterPlan.reviewId || project.masterPlan.reviewAudit?.reviewId || '',
        project.id,
        project.masterPlan.id,
        controller.signal,
      );
      if (!isCurrentSplitSubject()) return;
      if (result.success && result.data?.subPlans?.length) {
        setSubPlans(result.data.subPlans);
        setProjectStatus('SUBPLANS_GENERATED');
        message.success(`已拆分为 ${result.data.subPlans.length} 个子方案`);
        // 自动跳转到子方案 Tab
        onSwitchTab?.('subplans');
      } else {
        message.error(result.error || '拆分失败,未返回子方案');
        setProjectStatus('REVIEWED');
      }
    } catch (err) {
      if (isCancellationError(err)) {
        restoreCancelledOperation('splitting', projectId);
        return;
      }
      if (!isCurrentSplitSubject()) return;
      const msg = err instanceof Error ? err.message : String(err);
      console.error('拆分失败:', err);
      if (err instanceof SplitPlanError && err.code === 'SPLIT_BLOCKED') {
        const rules = err.issues.slice(0, 4).map((issue) => issue.rule).join(', ');
        message.error(`拆分被确定性校验阻断${rules ? `: ${rules}` : ''}`);
      } else {
        message.error(`拆分失败: ${msg}`);
      }
      setProjectStatus('REVIEWED');
    } finally {
      if (operationAbortRef.current === controller) operationAbortRef.current = null;
      setActiveOp(null);
      setIsStreaming(false);
    }
  };

  const severityIcon = (severity: string) => {
    switch (severity) {
      case 'ERROR':
        return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
      case 'WARN':
        return <WarningOutlined style={{ color: '#faad14' }} />;
      default:
        return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
    }
  };

  if (!project?.masterPlan) {
    return (
      <div style={{ padding: 16 }}>
        <Empty description="尚未生成方案" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const reviewing = activeOp === 'reviewing';
  const recoveringReview = project.status === 'REVIEWING' && !reviewing;
  const splitting = activeOp === 'splitting';
  const busy = activeOp !== null;
  const latestReviewProgress = reviewProgressEvents[reviewProgressEvents.length - 1];
  const reviewElapsedSeconds = reviewStartedAtMs ? Math.max(0, Math.floor((Date.now() - reviewStartedAtMs) / 1_000)) : 0;
  const reviewActivityText = lastReviewActivityAt ? new Date(lastReviewActivityAt).toLocaleTimeString() : '--';
  const stageFraction: Record<string, number> = {
    preparing: 0.15,
    reviewing: 0.45,
    analyzing: 0.7,
    fixing: 0.9,
    complete: 1,
  };
  const reviewPercent = latestReviewProgress
    ? latestReviewProgress.stage === 'complete' || !reviewing
      ? 100
      : Math.min(95, Math.round((
          ((latestReviewProgress.round ?? 1) - 1)
          + (stageFraction[latestReviewProgress.stage ?? 'preparing'] ?? 0.1)
        ) / (latestReviewProgress.totalRounds ?? 1) * 100))
    : 0;

  return (
    <div style={{ padding: '0 12px' }}>
      {/* 操作按钮 + 进度提示 */}
      <Space direction="vertical" size={8} style={{ width: '100%', marginBottom: 12 }}>
        {(project.status === 'PLAN_GENERATED' || project.status === 'REVIEWING') && (
          <>
            <Button type="primary" size="small" block onClick={handleReviewMasterPlan}
              loading={reviewing || recoveringReview} disabled={busy || recoveringReview}>
              {reviewing ? '正在审查方案…' : recoveringReview ? '正在恢复审核任务…' : '🔍 审查总方案'}
            </Button>
            {reviewing && (
              <>
                <Alert
                  type="info"
                  showIcon
                  icon={<LoadingOutlined />}
                  message="审核任务正在后台执行"
                  description={
                    <div style={{ fontSize: 11 }}>
                      <div>已运行 {reviewElapsedSeconds} 秒；最近状态更新：{reviewActivityText}</div>
                      <div>任务 ID：<Text code>{currentReviewId || project.masterPlan?.activeReviewId || '正在创建'}</Text></div>
                      <div>页面断线或切换后会通过持久化状态自动恢复，不再只依赖 SSE 连接。</div>
                    </div>
                  }
                  style={{ fontSize: 12, padding: '6px 12px' }}
                />
                <Button size="small" block danger onClick={cancelActiveOperation}>
                  {'\u53d6\u6d88\u672c\u6b21\u5ba1\u67e5'}
                </Button>
              </>
            )}
          </>
        )}

        {(project.status === 'REVIEWED' ||
          project.status === 'PLAN_CONFIRMED' ||
          project.status === 'SPLITTING' ||
          project.status === 'SUBPLANS_GENERATED') && (
          <>
            <Button type="primary" size="small" block onClick={handleSplitPlan} loading={splitting} disabled={busy}>
              {splitting ? '正在拆分子方案…' : '✂️ 拆分子方案'}
            </Button>
            {splitting && (
              <>
                <Alert
                  type="info"
                  showIcon
                  icon={<LoadingOutlined />}
                  message={'LLM \u6b63\u5728\u62c6\u5206\u5b50\u65b9\u6848\uff0c\u5b8c\u6210\u540e\u5c06\u81ea\u52a8\u8df3\u8f6c\u5230\u300c\u5b50\u65b9\u6848\u300d Tab\u2026'}
                  style={{ fontSize: 12, padding: '4px 12px' }}
                />
                <Button size="small" block danger onClick={cancelActiveOperation}>
                  {'\u53d6\u6d88\u672c\u6b21\u62c6\u5206'}
                </Button>
              </>
            )}
            {!splitting && project.status === 'SUBPLANS_GENERATED' && (
              <Alert
                type="success"
                showIcon
                message={
                  <span>
                    子方案已就绪,请前往 <Text strong>「子方案」</Text> Tab 查看并传输到 Part B
                  </span>
                }
                style={{ fontSize: 12, padding: '4px 12px' }}
              />
            )}
          </>
        )}
      </Space>

      {/* 审查阶段轨迹：审查完成后仍保留，避免快速请求只看到最终结论。 */}
      {reviewProgressEvents.length > 0 && (
        <div style={{ marginBottom: 8, padding: '8px 12px', background: '#e6f7ff', borderRadius: 4 }}>
          <Space direction="vertical" size={5} style={{ width: '100%' }}>
            <Space size={6}>
              {reviewing
                ? <LoadingOutlined spin style={{ color: '#1890ff' }} />
                : reviewProgressOutcome === 'error'
                  ? <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
                  : reviewProgressOutcome === 'warning'
                    ? <WarningOutlined style={{ color: '#fa8c16' }} />
                    : <CheckCircleOutlined style={{ color: '#52c41a' }} />}
              <Text strong style={{ fontSize: 12, color: '#1677ff' }}>
                {reviewing ? '审查执行进度' : '审查执行轨迹'}
              </Text>
              {latestReviewProgress?.round && (
                <Tag color="blue" style={{ margin: 0 }}>
                  第 {latestReviewProgress.round}/{latestReviewProgress.totalRounds ?? 1} 轮
                </Tag>
              )}
            </Space>
            <Progress percent={reviewPercent} size="small" status={reviewing ? 'active' : reviewProgressOutcome === 'error' ? 'exception' : reviewProgressOutcome === 'success' ? 'success' : 'normal'} showInfo />
            <div style={{ maxHeight: 150, overflowY: 'auto' }}>
              {reviewProgressEvents.map((event, index) => {
                const current = reviewing && index === reviewProgressEvents.length - 1;
                return (
                  <div key={`${index}-${event.stage}-${event.round}`} style={{ display: 'flex', gap: 6, alignItems: 'flex-start', marginTop: index === 0 ? 0 : 3 }}>
                    {current
                      ? <LoadingOutlined spin style={{ fontSize: 11, color: '#1890ff', marginTop: 3 }} />
                      : index === reviewProgressEvents.length - 1 && reviewProgressOutcome === 'error'
                        ? <CloseCircleOutlined style={{ fontSize: 11, color: '#ff4d4f', marginTop: 3 }} />
                        : index === reviewProgressEvents.length - 1 && reviewProgressOutcome === 'warning'
                          ? <WarningOutlined style={{ fontSize: 11, color: '#fa8c16', marginTop: 3 }} />
                          : <CheckCircleOutlined style={{ fontSize: 11, color: '#52c41a', marginTop: 3 }} />}
                    <Text style={{ fontSize: 11, color: current ? '#1677ff' : '#666' }}>{event.message}</Text>
                  </div>
                );
              })}
            </div>
          </Space>
        </div>
      )}

      {/* 审查结果 */}
      {reviewResult && (
        <div>
          <Text strong style={{ fontSize: 13 }}>
            审查结果 ({reviewResult.issues.length} 项
            {reviewResult.passes ? ',全部为建议' : ',含阻断性问题'})
          </Text>
          {reviewResult.status && (
            <Tag
              color={reviewResult.status === 'PASSED' ? 'green'
                : reviewResult.status === 'PASSED_WITH_WARNINGS' ? 'orange'
                  : 'red'}
              style={{ marginLeft: 6 }}
            >
              {reviewResult.status}
            </Tag>
          )}
          {reviewResult.audit && (
            <div style={{ marginTop: 6, padding: '6px 8px', background: '#fafafa', borderRadius: 4 }}>
              <div style={{ fontSize: 11, color: '#666' }}>
                Review ID: {reviewResult.audit.reviewId} / Rules: {reviewResult.audit.rulesetVersion}
              </div>
              {reviewResult.audit.rounds.map((round) => (
                <div key={`${round.round}-${round.rawResponseSha256}`} style={{ fontSize: 11, color: '#777', marginTop: 2 }}>
                  Round {round.round}: parse={round.parseStatus}, schema={round.schemaValidationStatus},
                  reference={round.referenceSummaryAvailable ? 'yes' : 'no'}
                  {round.referenceSnapshotId ? `, snapshot=${round.referenceSnapshotId}` : ''}
                  {round.contractSource ? `, contract=${round.contractSource}` : ''}
                  {round.responseKind ? `, response=${round.responseKind}` : ''}
                  {round.deterministicErrorCount != null
                    ? `, deterministic=${round.deterministicErrorCount}E/${round.deterministicWarningCount ?? 0}W`
                    : ''}
                  {round.diagnostic ? `, ${round.diagnostic}` : ''}
                </div>
              ))}
            </div>
          )}
          {reviewResult.reviewLog && reviewResult.reviewLog.length > 0 && (
            <div style={{ marginBottom: 8, marginTop: 4, padding: '6px 8px', background: '#f5f5f5', borderRadius: 4 }}>
              <Text style={{ fontSize: 11, color: '#999' }}>审查-修复过程:</Text>
              {reviewResult.reviewLog.map((log, i) => (
                <div key={i} style={{ fontSize: 11, color: '#666', marginTop: 2 }}>
                  <Tag color={log.action === 'review' ? (log.errorCount === 0 ? 'green' : 'red') : 'blue'} style={{ fontSize: 10, lineHeight: '16px' }}>
                    第{log.round}轮
                  </Tag>
                  {log.message}
                </div>
              ))}
            </div>
          )}
          <List
            size="small"
            dataSource={reviewResult.issues}
            renderItem={(issue) => (
              <List.Item style={{ padding: '4px 0', fontSize: 12 }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6 }}>
                  {severityIcon(issue.severity)}
                  <div>
                    <Tag
                      color={issue.severity === 'ERROR' ? 'red' : 'orange'}
                      style={{ fontSize: 10, lineHeight: '16px' }}
                    >
                      {issue.rule}
                    </Tag>
                    <Text style={{ fontSize: 12 }}>{issue.message}</Text>
                  </div>
                </div>
              </List.Item>
            )}
          />
        </div>
      )}

      {/* 方案摘要 */}
      {project.masterPlan.planContent && (
        <div style={{ marginTop: 12 }}>
          <Text type="secondary" style={{ fontSize: 11 }}>
            方案预览:
          </Text>
          <Paragraph
            ellipsis={{ rows: 8, expandable: true, symbol: '展开' }}
            style={{ fontSize: 11, color: '#666', marginTop: 4 }}
          >
            {project.masterPlan.planContent.slice(0, 800)}
          </Paragraph>
        </div>
      )}
    </div>
  );
};

export default ReviewFeedback;
