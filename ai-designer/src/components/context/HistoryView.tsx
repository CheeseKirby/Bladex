import React from 'react';
import { Timeline, Empty, Typography } from 'antd';
import { usePlanStore } from '../../store/planStore';

const { Text } = Typography;

const STAGE_NAMES: Record<string, string> = {
  DRAFT: '创建项目',
  ANALYZING: '分析需求',
  ANALYZED: '需求分析完成',
  PLANNING: '生成方案',
  PLAN_GENERATED: '方案已生成',
  REVIEWING: '审查方案',
  REVIEWED: '方案已审查',
  PLAN_CONFIRMED: '方案已确认',
  SPLITTING: '拆分子方案',
  SUBPLANS_GENERATED: '子方案已生成',
  SUBPLAN_REVIEWING: '审查子方案',
  SUBPLANS_REVIEWED: '子方案已审查',
  SUBPLANS_CONFIRMED: '子方案已确认',
  TRANSMITTING: '传输中',
  TRANSMITTED: '已传输到Part B',
};

const HistoryView: React.FC = () => {
  const project = usePlanStore((s) => s.project);

  if (!project) {
    return (
      <div style={{ padding: 16 }}>
        <Empty description="无项目" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const timelineItems = [
    {
      children: (
        <div>
          <Text strong>项目: {project.projectName}</Text>
          <br />
          <Text type="secondary" style={{ fontSize: 11 }}>
            创建于 {new Date().toLocaleString()}
          </Text>
        </div>
      ),
    },
    ...(project.status !== 'DRAFT'
      ? [
          {
            color: 'green',
            children: (
              <Text>{STAGE_NAMES[project.status] || project.status}</Text>
            ),
          },
        ]
      : []),
  ];

  return (
    <div style={{ padding: '8px 16px' }}>
      <Timeline items={timelineItems} style={{ fontSize: 12 }} />
      {project.masterPlan && (
        <div style={{ marginTop: 12 }}>
          <Text type="secondary" style={{ fontSize: 11 }}>
            方案版本: v{project.masterPlan.version}
            {project.masterPlan.llmModel && ` | 模型: ${project.masterPlan.llmModel}`}
            {project.masterPlan.llmTokensUsed &&
              ` | Token: ${project.masterPlan.llmTokensUsed}`}
          </Text>
        </div>
      )}
    </div>
  );
};

export default HistoryView;
