import React from 'react';
import { Descriptions, Tag, Empty, Typography } from 'antd';
import { usePlanStore } from '../../store/planStore';
import { MODULE_TYPES } from '../../types/plan';

const { Text } = Typography;

const RequirementSummary: React.FC = () => {
  const project = usePlanStore((s) => s.project);
  const canvasModules = usePlanStore((s) => s.canvasModules);

  if (!project) return <Empty description="无项目" />;

  const moduleCounts: Record<string, number> = {};
  canvasModules.forEach((m) => {
    moduleCounts[m.type] = (moduleCounts[m.type] || 0) + 1;
  });

  return (
    <div style={{ padding: '0 12px' }}>
      <Descriptions column={1} size="small" colon={false}>
        <Descriptions.Item label="项目名称">
          <Text strong>{project.projectName}</Text>
        </Descriptions.Item>
        <Descriptions.Item label="状态">
          <Tag>{project.status}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="需求">
          <Text style={{ fontSize: 12 }}>
            {project.rawRequirements || '未输入'}
          </Text>
        </Descriptions.Item>
        <Descriptions.Item label="模块统计">
          {Object.keys(moduleCounts).length > 0 ? (
            <div>
              {Object.entries(moduleCounts).map(([type, count]) => {
                const def = MODULE_TYPES.find((m) => m.type === type);
                return (
                  <Tag key={type} color={def?.color} style={{ margin: 2 }}>
                    {def?.icon} {def?.name} ×{count}
                  </Tag>
                );
              })}
            </div>
          ) : (
            <Text type="secondary">暂未添加模块</Text>
          )}
        </Descriptions.Item>
        {project.masterPlan && (
          <Descriptions.Item label="方案版本">
            v{project.masterPlan.version}
          </Descriptions.Item>
        )}
      </Descriptions>
    </div>
  );
};

export default RequirementSummary;
