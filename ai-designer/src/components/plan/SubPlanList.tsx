import React from 'react';
import { Tag, Empty, Typography, Collapse } from 'antd';
import { usePlanStore } from '../../store/planStore';
import PlanPreview from './PlanPreview';
import type { SubPlanStatus } from '../../types/plan';

const { Text } = Typography;

const STATUS_TAG: Record<SubPlanStatus, { color: string; text: string }> = {
  PENDING: { color: 'default', text: '待生成' },
  GENERATED: { color: 'processing', text: '已生成' },
  REVIEWED: { color: 'success', text: '已审查' },
  CONFIRMED: { color: 'blue', text: '已确认' },
  TRANSMITTED: { color: 'purple', text: '已传输' },
};

/** 子方案列表与管理 */
const SubPlanList: React.FC = () => {
  const subPlans = usePlanStore((s) => s.project?.subPlans || []);

  if (subPlans.length === 0) {
    return <Empty description="暂无子方案" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  return (
    <div style={{ padding: '0 8px' }}>
      <Collapse
        size="small"
        items={subPlans.map((sp) => {
          const tag = STATUS_TAG[sp.status] || STATUS_TAG.PENDING;
          return {
            key: sp.id,
            label: (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Text strong>#{sp.index}</Text>
                <Text>{sp.title}</Text>
                <Tag color={tag.color} style={{ fontSize: 10, lineHeight: '16px' }}>
                  {tag.text}
                </Tag>
                {sp.partBStatus && (
                  <Tag color="geekblue" style={{ fontSize: 10, lineHeight: '16px' }}>
                    Part B: {sp.partBStatus}
                  </Tag>
                )}
              </div>
            ),
            children: (
              <div>
                {sp.prerequisites.length > 0 && (
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    前置依赖: {sp.prerequisites.join(', ')}
                  </Text>
                )}
                {sp.planContent && (
                  <PlanPreview content={sp.planContent.slice(0, 600)} />
                )}
              </div>
            ),
          };
        })}
      />
    </div>
  );
};

export default SubPlanList;
