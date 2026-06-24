import React, { memo, useCallback, useState } from 'react';
import { Handle, Position, NodeProps } from 'reactflow';
import { Tag, Popconfirm } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import type { DraggedModule, ModuleType } from '../../types/plan';
import ModuleConfigModal from './ModuleConfigModal';

interface PlanNodeData {
  label: string;
  icon: string;
  type: ModuleType;
  color: string;
  module: DraggedModule | null;
}

const PlanNode: React.FC<NodeProps<PlanNodeData>> = ({ id, data, selected }) => {
  const removeModuleFromCanvas = usePlanStore((s) => s.removeModuleFromCanvas);
  const [configOpen, setConfigOpen] = useState(false);

  // 仅模块节点(挂在 data.module 上)允许删除/配置;主方案节点 module 为 null 不可删
  const removable = data.module != null;

  const handleRemove = useCallback(() => {
    removeModuleFromCanvas(id);
  }, [id, removeModuleFromCanvas]);

  // 配置按钮 — 阻止拖拽/点击事件冒泡到 ReactFlow,避免误触节点拖动
  const stopAndOpen = useCallback((e: React.MouseEvent | React.PointerEvent) => {
    e.stopPropagation();
    setConfigOpen(true);
  }, []);

  return (
    <div
      style={{
        position: 'relative',
        border: `2px solid ${selected ? '#1677ff' : data.color}`,
        borderRadius: 8,
        background: '#fff',
        minWidth: 180,
        boxShadow: selected ? '0 0 0 2px rgba(22,119,255,0.2)' : '0 1px 4px rgba(0,0,0,0.1)',
      }}
    >
      <Handle type="target" position={Position.Top} />
      {removable && (
        <>
          {/* 删除按钮 (配置入口已移到节点底部「✏️ 配置」文字, 避免重复) */}
          <Popconfirm
            title="移除该模块?"
            okText="移除"
            cancelText="取消"
            onConfirm={handleRemove}
            onCancel={(e) => e?.stopPropagation()}
          >
            <button
              type="button"
              aria-label="删除模块"
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
        </>
      )}
      <div
        style={{
          padding: '11px 16px',
          background: data.color,
          color: '#fff',
          borderRadius: '6px 6px 0 0',
          fontSize: 15,
          fontWeight: 600,
          display: 'flex',
          alignItems: 'center',
          gap: 7,
        }}
      >
        <span>{data.icon}</span>
        <span>{data.label}</span>
      </div>
      {data.module && (
        <div style={{ padding: '10px 16px', fontSize: 13 }}>
          {data.module.type === 'ENTITY' && data.module.config.tableName && (
            <Tag color="blue" style={{ margin: 2 }}>
              表: {data.module.config.tableName}
            </Tag>
          )}
          {data.module.type === 'API' && data.module.config.pathPrefix && (
            <Tag color="green" style={{ margin: 2 }}>
              路径: /{data.module.config.pathPrefix}
            </Tag>
          )}
          {data.module.type === 'PAGE' && data.module.config.pageName && (
            <Tag color="orange" style={{ margin: 2 }}>
              {data.module.config.pageType}: {data.module.config.pageName}
            </Tag>
          )}
          {data.module.type === 'FLOW' && data.module.config.processName && (
            <Tag color="purple" style={{ margin: 2 }}>
              流程: {data.module.config.processName}
            </Tag>
          )}
          {data.module.type === 'JOB' && data.module.config.jobName && (
            <Tag color="magenta" style={{ margin: 2 }}>
              任务: {data.module.config.jobName}
            </Tag>
          )}
          {data.module.type === 'FEIGN' && data.module.config.targetService && (
            <Tag color="cyan" style={{ margin: 2 }}>
              → {data.module.config.targetService}
            </Tag>
          )}
          {data.module.type === 'EXCEL' && data.module.config.entityName && (
            <Tag color="geekblue" style={{ margin: 2 }}>
              Excel: {data.module.config.entityName}
            </Tag>
          )}
          {/* 渲染业务字段 Tag(最多显示4个) */}
          {data.module.type === 'ENTITY' && data.module.config.fields && data.module.config.fields.length > 0 && (
            <div style={{ marginTop: 6, display: 'flex', flexWrap: 'wrap', gap: 3 }}>
              {data.module.config.fields.slice(0, 4).map((f, i) => (
                <Tag key={i} color="cyan" style={{ fontSize: 11, lineHeight: '16px', margin: 0 }}>
                  {f.name || `字段${i + 1}`}
                </Tag>
              ))}
              {data.module.config.fields.length > 4 && (
                <Tag style={{ fontSize: 11, lineHeight: '16px', margin: 0 }}>
                  +{data.module.config.fields.length - 4}
                </Tag>
              )}
            </div>
          )}
          {data.module.type === 'CONFIG' && (
            <Tag style={{ margin: 2 }}>配置: {data.module.config.configType}</Tag>
          )}
        </div>
      )}
      {/* 模块节点底部操作栏 — 用文字替代齿轮图标,让"配置"功能可见 */}
      {removable && (
        <div
          className="nodrag"
          style={{
            display: 'flex',
            justifyContent: 'center',
            gap: 12,
            padding: '4px 0 6px',
            borderTop: '1px solid #f0f0f0',
            fontSize: 13,
            color: '#888',
          }}
        >
          <span
            onClick={stopAndOpen}
            style={{ cursor: 'pointer', color: '#2f6bbf', userSelect: 'none', fontWeight: 500 }}
          >
            ✏️ 配置
          </span>
        </div>
      )}
      <Handle type="source" position={Position.Bottom} />

      {data.module && (
        <ModuleConfigModal
          module={data.module}
          open={configOpen}
          onClose={() => setConfigOpen(false)}
        />
      )}
    </div>
  );
};

export default memo(PlanNode);
