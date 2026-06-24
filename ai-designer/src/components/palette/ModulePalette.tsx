import React from 'react';
import { Typography, Space, Divider } from 'antd';
import { AppstoreOutlined } from '@ant-design/icons';
import { useDroppable } from '@dnd-kit/core';
import ModuleCard from './ModuleCard';
import { MODULE_TYPES } from '../../types/plan';

const { Text } = Typography;

const ModulePalette: React.FC = () => {
  const { setNodeRef, isOver } = useDroppable({ id: 'module-palette' });

  return (
    <div
      ref={setNodeRef}
      style={{
        padding: '14px 10px',
        background: isOver ? '#e6f4ff' : 'transparent',
        transition: 'background 0.2s',
      }}
    >
      <Space size={6} style={{ padding: '0 4px 12px' }}>
        <AppstoreOutlined style={{ color: '#2f6bbf', fontSize: 16 }} />
        <Text strong style={{ fontSize: 15, color: '#2c3338' }}>
          模块组件
        </Text>
      </Space>
      <Text type="secondary" style={{ display: 'block', fontSize: 12, padding: '0 4px 12px' }}>
        拖拽到画布,点 ⚙️ 配置字段
      </Text>
      <Divider style={{ margin: '0 0 10px' }} />
      <Space direction="vertical" size={6} style={{ width: '100%' }}>
        {MODULE_TYPES.map((mod) => (
          <ModuleCard key={mod.type} module={mod} />
        ))}
      </Space>
    </div>
  );
};

export default ModulePalette;
