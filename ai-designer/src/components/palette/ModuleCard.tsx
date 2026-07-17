import React from 'react';
import { Card } from 'antd';
import { useDraggable } from '@dnd-kit/core';
import type { ModuleTypeDef } from '../../types/plan';

interface ModuleCardProps {
  module: ModuleTypeDef;
}

const ModuleCard: React.FC<ModuleCardProps> = ({ module }) => {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: `draggable-${module.type}`,
    data: { module },
  });

  const style: React.CSSProperties = {
    cursor: 'grab',
    opacity: isDragging ? 0.5 : 1,
    transform: transform
      ? `translate3d(${transform.x}px, ${transform.y}px, 0)`
      : undefined,
    transition: 'box-shadow 0.2s, transform 0.2s',
    borderLeft: `3px solid ${module.color}`,
    userSelect: 'none',
  };

  return (
    <div ref={setNodeRef} {...listeners} {...attributes}>
      <Card
        size="small"
        hoverable
        style={style}
        styles={{ body: { padding: '8px 10px', display: 'flex', alignItems: 'center', gap: 8 } }}
      >
        <span style={{ fontSize: 22 }}>{module.icon}</span>
        <span style={{ fontSize: 14, fontWeight: 500, color: '#2c3338' }}>{module.name}</span>
      </Card>
    </div>
  );
};

export default ModuleCard;
