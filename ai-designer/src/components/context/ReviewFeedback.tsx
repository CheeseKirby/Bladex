import React, { useState } from 'react';
import { List, Tag, Empty, Typography, Button, Space, Alert, message } from 'antd';
import {
  CheckCircleOutlined,
  WarningOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import { splitPlan } from '../../services/api';

const { Text, Paragraph } = Typography;

/**
 * 当前进行中的操作类型,用于在按钮上显示 loading + 文案。
 * - 'reviewing' 审查总方案
 * - 'splitting' 拆分子方案
 * - null 空闲
 */
type ActiveOp = 'reviewing' | 'splitting' | null;

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
  const [reviewProgress, setReviewProgress] = useState('');

  const handleReviewMasterPlan = async () => {
    if (!project?.masterPlan || activeOp) return;
    setActiveOp('reviewing');
    setIsStreaming(true);
    setProjectStatus('REVIEWING');
    setReviewProgress('开始审查...');
    setReviewResult(null);
    try {
      const resp = await fetch('/api/llm/review-plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ planContent: project.masterPlan.planContent, stage: 'master' }),
      });
      if (!resp.ok || !resp.body) throw new Error(`HTTP ${resp.status}`);

      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const events = buffer.split('\n\n');
        buffer = events.pop() || '';
        for (const evt of events) {
          if (!evt.startsWith('data: ')) continue;
          try {
            const msg = JSON.parse(evt.slice(6));
            if (msg.type === 'progress') {
              setReviewProgress(msg.message);
            } else if (msg.type === 'done') {
              setReviewResult({
                passes: msg.data.passes,
                issues: msg.data.issues || [],
                reviewLog: msg.data.reviewLog || [],
              });
              setMasterPlan({
                ...project.masterPlan,
                reviewedContent: msg.data.fixedContent,
                reviewChangeLog: msg.data.changeLog,
                status: 'REVIEWED',
              });
              setReviewProgress('');
            } else if (msg.type === 'error') {
              throw new Error(msg.message);
            }
          } catch (e) { /* skip parse error */ }
        }
      }
      setProjectStatus('REVIEWED');
      message.success('审查完成');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      console.error('审查失败:', err);
      message.error(`审查失败: ${msg}`);
      setProjectStatus('PLAN_GENERATED');
    } finally {
      setActiveOp(null);
      setIsStreaming(false);
    }
  };

  const handleSplitPlan = async () => {
    if (!project?.masterPlan || activeOp) return;
    setActiveOp('splitting');
    setIsStreaming(true);
    setProjectStatus('SPLITTING');
    try {
      const result = await splitPlan(project.masterPlan.reviewedContent || project.masterPlan.planContent);
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
      const msg = err instanceof Error ? err.message : String(err);
      console.error('拆分失败:', err);
      message.error(`拆分失败: ${msg}`);
      setProjectStatus('REVIEWED');
    } finally {
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
  const splitting = activeOp === 'splitting';
  const busy = activeOp !== null;

  return (
    <div style={{ padding: '0 12px' }}>
      {/* 操作按钮 + 进度提示 */}
      <Space direction="vertical" size={8} style={{ width: '100%', marginBottom: 12 }}>
        {(project.status === 'PLAN_GENERATED' || project.status === 'REVIEWING') && (
          <>
            <Button type="primary" size="small" block onClick={handleReviewMasterPlan} loading={reviewing} disabled={busy}>
              {reviewing ? '正在审查方案…' : '🔍 审查总方案'}
            </Button>
            {reviewing && (
              <Alert
                type="info"
                showIcon
                icon={<LoadingOutlined />}
                message="LLM 正在审查方案,通常需要 10-30 秒,请勿重复点击…"
                style={{ fontSize: 12, padding: '4px 12px' }}
              />
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
              <Alert
                type="info"
                showIcon
                icon={<LoadingOutlined />}
                message="LLM 正在拆分子方案,通常需要 10-30 秒,完成后将自动跳转到「子方案」Tab…"
                style={{ fontSize: 12, padding: '4px 12px' }}
              />
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

      {/* 审查中实时进度 */}
      {activeOp === 'reviewing' && reviewProgress && (
        <div style={{ marginBottom: 8, padding: '8px 12px', background: '#e6f7ff', borderRadius: 4, display: 'flex', alignItems: 'center', gap: 8 }}>
          <LoadingOutlined spin style={{ fontSize: 14, color: '#1890ff' }} />
          <Text style={{ fontSize: 12, color: '#1890ff' }}>{reviewProgress}</Text>
        </div>
      )}

      {/* 审查结果 */}
      {reviewResult && (
        <div>
          <Text strong style={{ fontSize: 13 }}>
            审查结果 ({reviewResult.issues.length} 项
            {reviewResult.passes ? ',全部为建议' : ',含阻断性问题'})
          </Text>
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
