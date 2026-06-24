import React, { memo, useCallback } from 'react';
import { Handle, Position, NodeProps } from 'reactflow';
import { Tag, Typography, Popconfirm } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import type { SubPlanStatus } from '../../types/plan';

const { Text } = Typography;

interface SubPlanNodeData {
  label: string;
  index: number;
  status: SubPlanStatus;
  prerequisites: string[];
}

const STATUS_MAP: Record<SubPlanStatus, { color: string; text: string }> = {
  PENDING: { color: 'default', text: '待处理' },
  GENERATED: { color: 'processing', text: '已生成' },
  REVIEWED: { color: 'success', text: '已审查' },
  CONFIRMED: { color: 'success', text: '已确认' },
  TRANSMITTED: { color: 'purple', text: '已传输' },
};

const SubPlanNode: React.FC<NodeProps<SubPlanNodeData>> = ({ id, data, selected }) => {
  const statusInfo = STATUS_MAP[data.status] || STATUS_MAP.PENDING;
  const removeSubPlan = usePlanStore((s) => s.removeSubPlan);

  const handleRemove = useCallback(() => {
    removeSubPlan(id);
  }, [id, removeSubPlan]);

  return (
    <div
      style={{
        position: 'relative',
        border: `2px solid ${selected ? '#1677ff' : '#d9d9d9'}`,
        borderRadius: 8,
        background: '#fff',
        minWidth: 200,
        boxShadow: selected ? '0 0 0 2px rgba(22,119,255,0.2)' : '0 1px 3px rgba(0,0,0,0.08)',
      }}
    >
      <Handle type="target" position={Position.Top} />
      <Popconfirm
        title="移除该子方案?"
        description="同时清理其他子方案中对它的依赖"
        okText="移除"
        cancelText="取消"
        onConfirm={handleRemove}
        onCancel={(e) => e?.stopPropagation()}
      >
        <button
          type="button"
          aria-label="删除子方案"
          onClick={(e) => e.stopPropagation()}
          onMouseDown={(e) => e.stopPropagation()}
          className="nodrag"
          style={{
            position: 'absolute',
            top: -10,
            right: -10,
            width: 22,
            height: 22,
            borderRadius: '50%',
            border: '1px solid #ffa39e',
            background: '#fff1f0',
            color: '#cf1322',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 0,
            fontSize: 12,
            lineHeight: 1,
            boxShadow: '0 1px 2px rgba(0,0,0,0.15)',
            zIndex: 10,
          }}
        >
          <CloseOutlined style={{ fontSize: 11 }} />
        </button>
      </Popconfirm>
      <div style={{ padding: '10px 14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
          <Text strong style={{ fontSize: 13 }}>
            #{data.index} {data.label}
          </Text>
          <Tag color={statusInfo.color} style={{ margin: 0 }}>
            {statusInfo.text}
          </Tag>
        </div>
        {data.prerequisites.length > 0 && (
          <div style={{ fontSize: 11, color: '#999' }}>
            依赖: {data.prerequisites.map((p) => `#${p.slice(-4)}`).join(', ')}
          </div>
        )}
      </div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
};

export default memo(SubPlanNode);
