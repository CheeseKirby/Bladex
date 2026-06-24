import { useCallback } from 'react';
import { usePlanStore } from '../store/planStore';
import { MODULE_TYPES } from '../types/plan';
import type { DraggedModule, ModuleType } from '../types/plan';

/**
 * dnd-kit 拖拽逻辑封装
 *
 * 处理模块拖放到画布的逻辑。
 */
export function useDragDrop() {
  const addModuleToCanvas = usePlanStore((s) => s.addModuleToCanvas);
  const removeModuleFromCanvas = usePlanStore((s) => s.removeModuleFromCanvas);

  /** 根据模块类型创建新模块 */
  const createModule = useCallback(
    (type: ModuleType): DraggedModule | null => {
      const def = MODULE_TYPES.find((m) => m.type === type);
      if (!def) return null;
      return {
        id: '',
        type: def.type,
        name: def.name,
        icon: def.icon,
        color: def.color,
        config: { ...def.defaultConfig },
      };
    },
    []
  );

  /** 拖拽结束处理 */
  const handleDrop = useCallback(
    (type: ModuleType) => {
      const mod = createModule(type);
      if (mod) addModuleToCanvas(mod);
    },
    [createModule, addModuleToCanvas]
  );

  /** 从画布删除模块 */
  const handleRemove = useCallback(
    (id: string) => {
      removeModuleFromCanvas(id);
    },
    [removeModuleFromCanvas]
  );

  return { createModule, handleDrop, handleRemove };
}
