import React, { useState } from 'react';
import { List, Tag, Empty, Typography, Button, Space, Alert, message } from 'antd';
import {
  CheckCircleOutlined,
  WarningOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import { reviewPlan, splitPlan } from '../../services/api';

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

  const handleReviewMasterPlan = async () => {
    if (!project?.masterPlan || activeOp) return;
    setActiveOp('reviewing');
    setIsStreaming(true);
    setProjectStatus('REVIEWING');
    try {
      const result = await reviewPlan(project.masterPlan.planContent, 'master');
      if (result.success) {
        setReviewResult({
          passes: result.data.passes,
          issues: result.data.issues || [],
        });
        setMasterPlan({
          ...project.masterPlan,
          reviewedContent: result.data.fixedContent,
          reviewChangeLog: result.data.changeLog,
          status: 'REVIEWED',
        });
        setProjectStatus('REVIEWED');
        message.success(`审查完成,发现 ${result.data.issues?.length || 0} 项问题`);
      } else {
        message.error(result.error || '审查失败');
        setProjectStatus('PLAN_GENERATED');
      }
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

      {/* 审查结果 */}
      {reviewResult && (
        <div>
          <Text strong style={{ fontSize: 13 }}>
            审查结果 ({reviewResult.issues.length} 项
            {reviewResult.passes ? ',全部为建议' : ',含阻断性问题'})
          </Text>
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
