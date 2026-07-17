import React, { useState } from 'react';
import { Typography, Tabs, Badge } from 'antd';
import { usePlanStore } from '../../store/planStore';
import RequirementSummary from './RequirementSummary';
import ReviewFeedback from './ReviewFeedback';
import SubPlanNavigator from './SubPlanNavigator';
import GeneratedFilesView from './GeneratedFilesView';
import ExecutionTimelinePanel from './ExecutionTimelinePanel';
import HistoryView from './HistoryView';

const { Title } = Typography;

const ContextPanel: React.FC = () => {
  const project = usePlanStore((s) => s.project);
  const reviewResult = usePlanStore((s) => s.reviewResult);
  const generatedFiles = usePlanStore((s) => s.generatedFiles);
  const partBOverallStatus = usePlanStore((s) => s.partBOverallStatus);
  const receptionId = usePlanStore((s) => s.receptionId);
  const executionTimeline = usePlanStore((s) => s.executionTimeline);
  // 受控的当前 Tab — 允许子组件(如拆分完成后)主动切换
  const [activeTab, setActiveTab] = useState('summary');
  const terminalTimelineCount = executionTimeline?.subPlanTimelines.filter((item) =>
    ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'SKIPPED'].includes(item.status || '')
  ).length ?? 0;

  if (!project) {
    return (
      <div style={{ padding: 20, color: '#9aa3ad', fontSize: 15 }}>
        <Title level={5} style={{ fontSize: 15, color: '#9aa3ad' }}>
          上下文面板
        </Title>
        <p>创建项目后，此处将展示需求摘要、LLM审查反馈和子方案导航。</p>
      </div>
    );
  }

  const tabItems = [
    {
      key: 'summary',
      label: '需求摘要',
      children: <RequirementSummary />,
    },
    {
      key: 'review',
      label: `审查反馈${reviewResult ? ` (${reviewResult.issues.length})` : ''}`,
      children: <ReviewFeedback onSwitchTab={setActiveTab} />,
    },
    {
      key: 'subplans',
      label: `子方案 (${project.subPlans.length})`,
      children: <SubPlanNavigator onSwitchTab={setActiveTab} />,
    },
    {
      key: 'progress',
      label: (
        <span>
          执行进度
          {executionTimeline && executionTimeline.totalSubPlans > 0 && (
            <Badge
              count={`${terminalTimelineCount}/${executionTimeline.totalSubPlans}`}
              style={{
                backgroundColor:
                  partBOverallStatus === 'COMPLETED'
                    ? '#52c41a'
                    : partBOverallStatus === 'COMPLETED_WITH_ERRORS'
                    ? '#d46b08'
                    : partBOverallStatus === 'FAILED'
                    ? '#cf1322'
                    : '#1677ff',
                marginLeft: 4,
                fontSize: 10,
              }}
            />
          )}
        </span>
      ),
      disabled: !receptionId,
      children: <ExecutionTimelinePanel />,
    },
    {
      key: 'files',
      label: (
        <span>
          生成文件{' '}
          <Badge
            count={generatedFiles.length}
            style={{ backgroundColor: partBOverallStatus === 'COMPLETED' ? '#52c41a' : '#1677ff' }}
            showZero={false}
          />
        </span>
      ),
      children: <GeneratedFilesView />,
    },
    {
      key: 'history',
      label: '历史',
      children: <HistoryView />,
    },
  ];

  return (
    <div style={{ padding: '12px 0' }}>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        size="small"
        tabBarStyle={{ padding: '0 16px', margin: 0, borderBottom: '1px solid #e6e9ee' }}
        items={tabItems}
      />
    </div>
  );
};

export default ContextPanel;
