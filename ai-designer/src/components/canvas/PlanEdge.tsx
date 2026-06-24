import React, { memo, useCallback } from 'react';
import { BaseEdge, EdgeLabelRenderer, EdgeProps, getBezierPath } from 'reactflow';
import { CloseOutlined } from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';

/**
 * 自定义连线（依赖关系）— hover/选中显示 × 删除按钮
 *
 * 删除途径:
 * 1. 点击 × 按钮 → 直接从 target 子方案的 prerequisites 中移除 source
 * 2. 选中连线 + Delete/Backspace → ReactFlow 触发 onEdgesChange,
 *    PlanCanvas 中的 handleEdgesChange 会同步删除 store 中的 prerequisites
 */
const PlanEdge: React.FC<EdgeProps> = ({
  id,
  source,
  target,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  selected,
}) => {
  const removeSubPlanDependency = usePlanStore((s) => s.removeSubPlanDependency);

  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  const onEdgeClick = useCallback(
    (evt: React.MouseEvent) => {
      evt.stopPropagation();
      // source 和 target 是 subPlan 节点 ID(即子方案 id),直接更新 store
      // 注意:连线也可能是模块→模块,那些不在子方案里,所以不会炸
      removeSubPlanDependency(source, target);
    },
    [source, target, removeSubPlanDependency]
  );

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={{
          stroke: selected ? '#1677ff' : '#999',
          strokeWidth: selected ? 3 : 2,
          transition: 'stroke 0.15s',
          cursor: 'pointer',
        }}
        markerEnd={markerEnd}
      />
      <EdgeLabelRenderer>
        <button
          type="button"
          className="nodrag nopan"
          onClick={onEdgeClick}
          style={{
            position: 'absolute',
            transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
            pointerEvents: 'all',
            width: 20,
            height: 20,
            borderRadius: '50%',
            border: '1px solid #ffa39e',
            background: selected ? '#ff4d4f' : '#fff1f0',
            color: selected ? '#fff' : '#cf1322',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 0,
            fontSize: 10,
            lineHeight: 1,
            opacity: selected ? 1 : 0,
            transition: 'opacity 0.15s, background 0.15s',
            zIndex: 100,
            boxShadow: selected ? '0 1px 4px rgba(207,19,34,0.3)' : 'none',
          }}
          onMouseEnter={(e) => {
            if (!selected) (e.currentTarget as HTMLButtonElement).style.opacity = '1';
          }}
          onMouseLeave={(e) => {
            if (!selected) (e.currentTarget as HTMLButtonElement).style.opacity = '0';
          }}
          title="删除依赖连线"
        >
          <CloseOutlined style={{ fontSize: 10 }} />
        </button>
      </EdgeLabelRenderer>
    </>
  );
};

export default memo(PlanEdge);