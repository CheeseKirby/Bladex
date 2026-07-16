import React, { useState } from 'react';
import { Empty, Progress, Tag, Typography, Collapse, Timeline, Tooltip, Button, Space, Card } from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  ClockCircleOutlined,
  MinusCircleOutlined,
  ReloadOutlined,
  WarningOutlined,
  FileTextOutlined,
  ExperimentOutlined,
  CheckOutlined,
  EditOutlined,
} from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import { usePartBStatusPoll } from '../../hooks/usePartBStatusPoll';
import type { SubPlanTimeline, TimelineStep } from '../../types/api';

const { Text } = Typography;

const STATUS_META: Record<string, { color: string; text: string; icon: React.ReactNode }> = {
  QUEUED: { color: 'default', text: '排队中', icon: <ClockCircleOutlined /> },
  EXECUTING: { color: 'gold', text: '执行中', icon: <LoadingOutlined /> },
  COMPLETED: { color: 'green', text: '已完成', icon: <CheckCircleOutlined /> },
  FAILED: { color: 'red', text: '失败', icon: <CloseCircleOutlined /> },
  SKIPPED: { color: 'default', text: '已跳过', icon: <MinusCircleOutlined /> },
};

const STAGE_META: Record<string, { label: string; icon: React.ReactNode }> = {
  CHANGE_EVALUATION: { label: '变更评估', icon: <ExperimentOutlined /> },
  CODE_GENERATION: { label: '代码生成', icon: <EditOutlined /> },
  VALIDATION: { label: '规范校验', icon: <CheckOutlined /> },
  FILE_WRITE: { label: '文件写入', icon: <FileTextOutlined /> },
  BUILD_VERIFY: { label: '编译验证', icon: <CheckOutlined /> },
  SELF_REVIEW: { label: '自我审查', icon: <ExperimentOutlined /> },
};

function formatDuration(start?: string, end?: string): string {
  if (!start) return '';
  const s = new Date(start).getTime();
  const e = end ? new Date(end).getTime() : Date.now();
  const ms = Math.max(0, e - s);
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s`;
}

function stepDotColor(status?: string): string {
  switch (status) {
    case 'SUCCESS': return 'green';
    case 'FAILED': return 'red';
    case 'SKIPPED': return 'gray';
    default: return 'blue';
  }
}

function StepDot({ stage }: { stage?: string }): React.ReactElement {
  const meta = stage ? STAGE_META[stage] : null;
  return <span style={{ fontSize: 14 }}>{meta?.icon || <CheckOutlined />}</span>;
}

const ExecutionTimelinePanel: React.FC = () => {
  const receptionId = usePlanStore((s) => s.receptionId);
  const timeline = usePlanStore((s) => s.executionTimeline);
  const { refreshTimeline } = usePartBStatusPoll();
  const [refreshing, setRefreshing] = useState(false);

  if (!receptionId) {
    return (
      <div style={{ padding: 16 }}>
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="尚未传输方案到 Part B"
        />
        <Text type="secondary" style={{ fontSize: 11, display: 'block', textAlign: 'center' }}>
          传输后此处实时显示每个子方案的执行步骤
        </Text>
      </div>
    );
  }

  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      await refreshTimeline();
    } finally {
      setRefreshing(false);
    }
  };

  if (!timeline) {
    return (
      <div style={{ padding: 16 }}>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚无执行数据" />
        <div style={{ textAlign: 'center', marginTop: 8 }}>
          <Button size="small" icon={<ReloadOutlined />} loading={refreshing} onClick={handleRefresh}>
            刷新
          </Button>
        </div>
      </div>
    );
  }

  const total = timeline.totalSubPlans || 0;
  const completed = timeline.completedSubPlans || 0;
  const failed = timeline.failedSubPlans || 0;
  const inProgress = timeline.subPlanTimelines.filter((sp) => sp.status === 'EXECUTING').length;
  const percent = total > 0 ? Math.round(((completed + failed) / total) * 100) : 0;

  const overallTag = STATUS_META[timeline.overallStatus || ''] || STATUS_META.QUEUED;

  return (
    <div style={{ padding: '0 8px' }}>
      <Card size="small" style={{ marginBottom: 8 }}>
        <Space direction="vertical" size={6} style={{ width: '100%' }}>
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <Space size={4}>
              <Text strong style={{ fontSize: 12 }}>整体进度</Text>
              <Tag color={overallTag.color} style={{ marginLeft: 4 }}>
                {overallTag.icon} {overallTag.text}
              </Tag>
            </Space>
            <Button size="small" icon={<ReloadOutlined />} loading={refreshing} onClick={handleRefresh}>
              刷新
            </Button>
          </Space>
          <Progress
            percent={percent}
            size="small"
            status={failed > 0 ? 'exception' : timeline.overallStatus === 'COMPLETED' ? 'success' : 'active'}
            format={() => `${completed + failed}/${total}`}
          />
          <Space size={12} wrap style={{ fontSize: 11 }}>
            <Text type="secondary">✅ 完成 <Text strong style={{ color: '#52c41a' }}>{completed}</Text></Text>
            {inProgress > 0 && (
              <Text type="secondary">⏳ 执行中 <Text strong style={{ color: '#fa8c16' }}>{inProgress}</Text></Text>
            )}
            {failed > 0 && (
              <Text type="secondary">❌ 失败 <Text strong style={{ color: '#cf1322' }}>{failed}</Text></Text>
            )}
            <Text type="secondary">📦 共 {total} 个子方案</Text>
          </Space>
        </Space>
      </Card>

      <Collapse
        size="small"
        defaultActiveKey={timeline.subPlanTimelines
          .filter((sp) => sp.status === 'EXECUTING' || sp.status === 'FAILED')
          .map((sp) => String(sp.subPlanId))}
        items={timeline.subPlanTimelines.map((sp) => buildPanelItem(sp))}
      />
    </div>
  );
};

function buildPanelItem(sp: SubPlanTimeline) {
  const meta = STATUS_META[sp.status || 'QUEUED'] || STATUS_META.QUEUED;
  const duration = formatDuration(sp.startedAt, sp.completedAt);
  return {
    key: String(sp.subPlanId),
    label: (
      <Space size={6} style={{ fontSize: 12 }}>
        <Text strong>#{sp.index ?? '-'} {sp.title}</Text>
        <Tag color={meta.color} style={{ marginLeft: 4 }}>
          {meta.icon} {meta.text}
        </Tag>
        {sp.fileCount > 0 && <Tag color="blue">{sp.fileCount} 个文件</Tag>}
        {duration && <Text type="secondary" style={{ fontSize: 11 }}>耗时 {duration}</Text>}
      </Space>
    ),
    children: (
      <div style={{ fontSize: 12 }}>
        {sp.errorMessage && (
          <div style={{ background: '#fff1f0', border: '1px solid #ffa39e', borderRadius: 4, padding: 6, marginBottom: 8 }}>
            <Text type="danger" style={{ fontSize: 11 }}>失败原因: {sp.errorMessage}</Text>
          </div>
        )}
        {sp.steps.length === 0 ? (
          <Text type="secondary" style={{ fontSize: 11 }}>
            {sp.status === 'QUEUED' ? '等待执行...' : sp.status === 'EXECUTING' ? '执行中...' : '尚无步骤记录'}
          </Text>
        ) : (
          <Timeline
            items={sp.steps.map((step) => buildTimelineEntry(step))}
          />
        )}
      </div>
    ),
  };
}

function buildTimelineEntry(step: TimelineStep) {
  const stageMeta = step.stage ? STAGE_META[step.stage] : null;
  return {
    color: stepDotColor(step.status),
    dot: <StepDot stage={step.stage} />,
    children: (
      <div style={{ fontSize: 11 }}>
        <Space size={4} wrap>
          <Text strong>{stageMeta?.label ?? step.stage ?? '步骤'}</Text>
          {step.action && <Tag>{step.action}</Tag>}
          {step.status && (
            <Tag color={step.status === 'SUCCESS' ? 'green' : step.status === 'FAILED' ? 'red' : 'default'}>
              {step.status}
            </Tag>
          )}
        </Space>
        {step.reason && (
          <Tooltip title={step.reason} placement="topLeft">
            <div style={{ color: '#666', marginTop: 2, fontSize: 11, maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {step.reason}
            </div>
          </Tooltip>
        )}
        {step.filePath && (
          <div style={{ color: '#999', fontSize: 10, marginTop: 2, wordBreak: 'break-all' }}>
            {step.filePath}
          </div>
        )}
        {step.createTime && (
          <div style={{ color: '#bbb', fontSize: 10 }}>
            {new Date(step.createTime).toLocaleTimeString()}
          </div>
        )}
      </div>
    ),
  };
}

export default ExecutionTimelinePanel;
