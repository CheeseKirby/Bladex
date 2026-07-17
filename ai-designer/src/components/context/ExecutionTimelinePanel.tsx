import React, { useState } from 'react';
import { Empty, Progress, Tag, Typography, Collapse, Timeline, Tooltip, Button, Space, Card, message } from 'antd';
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
  COMPLETED_WITH_ERRORS: { color: 'orange', text: '完成但有错误', icon: <WarningOutlined /> },
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
  CROSS_FILE_VALIDATION: { label: '跨文件校验', icon: <CheckOutlined /> },
  FIX_LOOP: { label: '自动修复', icon: <EditOutlined /> },
  REFERENCE_SELECTION: { label: 'Reference selection', icon: <ExperimentOutlined /> },
  PROJECT_QUALITY_VALIDATION: { label: 'Artifact quality validation', icon: <CheckOutlined /> },
  COMPILE_VERIFICATION: { label: 'Compile verification', icon: <CheckOutlined /> },
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
      const refreshed = await refreshTimeline();
      if (!refreshed) message.error('执行进度加载失败，请检查 Part B 日志或稍后重试');
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

  const total = timeline.totalSubPlans || timeline.subPlanTimelines.length;
  const completed = timeline.subPlanTimelines.filter((sp) => sp.status === 'COMPLETED').length;
  const partial = timeline.subPlanTimelines.filter((sp) => sp.status === 'COMPLETED_WITH_ERRORS').length;
  const failed = timeline.subPlanTimelines.filter((sp) => sp.status === 'FAILED').length;
  const skipped = timeline.subPlanTimelines.filter((sp) => sp.status === 'SKIPPED').length;
  const inProgress = timeline.subPlanTimelines.filter((sp) => sp.status === 'EXECUTING').length;
  const terminalCount = completed + partial + failed + skipped;
  const percent = total > 0 ? Math.round((terminalCount / total) * 100) : 0;

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
            status={failed > 0 ? 'exception' : partial > 0 ? 'normal' : timeline.overallStatus === 'COMPLETED' ? 'success' : 'active'}
            format={() => `${terminalCount}/${total}`}
          />
          <Space size={12} wrap style={{ fontSize: 11 }}>
            <Text type="secondary">✅ 完成 <Text strong style={{ color: '#52c41a' }}>{completed}</Text></Text>
            {inProgress > 0 && (
              <Text type="secondary">⏳ 执行中 <Text strong style={{ color: '#fa8c16' }}>{inProgress}</Text></Text>
            )}
            {partial > 0 && (
              <Text type="secondary">⚠️ 完成但有错误 <Text strong style={{ color: '#d46b08' }}>{partial}</Text></Text>
            )}
            {failed > 0 && (
              <Text type="secondary">❌ 失败 <Text strong style={{ color: '#cf1322' }}>{failed}</Text></Text>
            )}
            <Text type="secondary">📦 共 {total} 个子方案</Text>
          </Space>
        </Space>
      </Card>

      <Card size="small" style={{ marginBottom: 8 }} styles={{ body: { padding: 10 } }}>
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          <Space size={6} wrap>
            <Text strong style={{ fontSize: 12 }}>{'\u4ea7\u7269\u8d28\u91cf'}</Text>
            {timeline.moduleName && <Tag color="blue">{'\u6a21\u5757'} {timeline.moduleName}</Tag>}
            {timeline.entityName && <Tag>{'\u5b9e\u4f53'} {timeline.entityName}</Tag>}
            {timeline.frameworkVersion && <Tag>BladeX {timeline.frameworkVersion}</Tag>}
            {timeline.javaVersion && <Tag>Java {timeline.javaVersion}</Tag>}
          </Space>
          <Space size={6} wrap>
            <Tag color={(timeline.qualityErrorCount ?? 0) > 0 ? 'red' : 'green'}>
              {'\u9759\u6001\u6821\u9a8c'} {(timeline.qualityErrorCount ?? 0) > 0 ? `${timeline.qualityErrorCount} ERROR` : '\u901a\u8fc7'}
            </Tag>
            {(timeline.qualityWarningCount ?? 0) > 0 && (
              <Tag color="gold">{timeline.qualityWarningCount} WARN</Tag>
            )}
            <Tag color={timeline.compileVerificationStatus === 'PASSED' ? 'green'
              : timeline.compileVerificationStatus === 'FAILED' ? 'red'
                : timeline.compileVerificationStatus === 'SKIPPED_DEPENDENCIES_UNAVAILABLE' ? 'gold' : 'default'}>
              {timeline.compileVerificationStatus === 'PASSED' ? '\u7f16\u8bd1\u5df2\u9a8c\u8bc1'
                : timeline.compileVerificationStatus === 'FAILED' ? '\u7f16\u8bd1\u5931\u8d25'
                  : timeline.compileVerificationStatus === 'SKIPPED_DEPENDENCIES_UNAVAILABLE'
                    ? '\u7f16\u8bd1\u672a\u9a8c\u8bc1\uff08\u4f9d\u8d56\u4e0d\u53ef\u7528\uff09' : '\u7f16\u8bd1\u5c1a\u672a\u6267\u884c'}
            </Tag>
          </Space>
          {timeline.outputDirectory && (
            <Tooltip title={timeline.outputDirectory}>
              <Text type="secondary" ellipsis style={{ maxWidth: 280, fontSize: 10 }}>
                {'\u8f93\u51fa\uff1a'}{timeline.outputDirectory}
              </Text>
            </Tooltip>
          )}
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
