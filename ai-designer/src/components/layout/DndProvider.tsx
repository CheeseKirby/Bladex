import React, { useState } from 'react';
import { DndContext, DragOverlay, DragStartEvent, DragEndEvent, pointerWithin } from '@dnd-kit/core';
import { usePlanStore } from '../../store/planStore';
import { MODULE_TYPES } from '../../types/plan';
import type { DraggedModule, ModuleTypeDef } from '../../types/plan';
import { Card } from 'antd';

interface DndProviderProps {
  children: React.ReactNode;
}

/**
 * @dnd-kit 拖拽上下文，连接模块面板和画布
 */
const DndProvider: React.FC<DndProviderProps> = ({ children }) => {
  const addModuleToCanvas = usePlanStore((s) => s.addModuleToCanvas);
  const [activeModule, setActiveModule] = useState<ModuleTypeDef | null>(null);

  const handleDragStart = (event: DragStartEvent) => {
    const { active } = event;
    const type = String(active.id).replace('draggable-', '');
    const mod = MODULE_TYPES.find((m) => m.type === type);
    if (mod) setActiveModule(mod);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { over, active } = event;

    // 拖到画布区域
    if (over && (over.id === 'plan-canvas-drop' || over.id.toString().startsWith('plan-canvas'))) {
      const type = String(active.id).replace('draggable-', '');
      const mod = MODULE_TYPES.find((m) => m.type === type);
      if (mod) {
        const newModule: DraggedModule = {
          id: '',
          type: mod.type,
          name: mod.name,
          icon: mod.icon,
          color: mod.color,
          config: { ...mod.defaultConfig },
        };
        addModuleToCanvas(newModule);
      }
    }

    setActiveModule(null);
  };

  return (
    <DndContext
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
      collisionDetection={pointerWithin}
    >
      {children}
      <DragOverlay>
        {activeModule ? (
          <Card
            size="small"
            style={{
              borderLeft: `3px solid ${activeModule.color}`,
              opacity: 0.85,
              boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
            }}
            styles={{ body: { padding: '8px 10px', display: 'flex', alignItems: 'center', gap: 8 } }}
          >
            <span style={{ fontSize: 20 }}>{activeModule.icon}</span>
            <span style={{ fontSize: 12, fontWeight: 500 }}>{activeModule.name}</span>
          </Card>
        ) : null}
      </DragOverlay>
    </DndContext>
  );
};

export default DndProvider;
