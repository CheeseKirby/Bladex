import React, { useState } from 'react';
import { Layout, Button, Select, Space, Badge, Typography, Tooltip, App } from 'antd';
import {
  SaveOutlined,
  ExportOutlined,
  SettingOutlined,
  PlusOutlined,
  RocketOutlined,
  ThunderboltFilled,
} from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import { ORDER_MANAGEMENT_DEMO } from '../../demo/orderManagement';
import LlmConfigModal from '../settings/LlmConfigModal';

const { Header } = Layout;
const { Text } = Typography;

interface TopBarProps {
  onNewProject: () => void;
  onSave: () => void;
  onExport: () => void;
}

const TopBar: React.FC<TopBarProps> = ({ onNewProject, onSave, onExport }) => {
  const project = usePlanStore((s) => s.project);
  const isStreaming = usePlanStore((s) => s.isStreaming);
  const loadDemo = usePlanStore((s) => s.loadDemo);
  const { message } = App.useApp();

  const handleLoadDemo = () => {
    loadDemo(ORDER_MANAGEMENT_DEMO);
    message.success(`已载入示例: ${ORDER_MANAGEMENT_DEMO.projectName} — 可直接传输到 Part B`);
  };

  const [llmModalOpen, setLlmModalOpen] = useState(false);

  const statusLabel: Record<string, { text: string; color: string }> = {
    DRAFT: { text: '草稿', color: 'default' },
    ANALYZING: { text: '分析中', color: 'processing' },
    ANALYZED: { text: '已分析', color: 'success' },
    PLANNING: { text: '方案生成中', color: 'processing' },
    PLAN_GENERATED: { text: '方案已生成', color: 'success' },
    REVIEWING: { text: '审查中', color: 'processing' },
    REVIEWED: { text: '已审查', color: 'success' },
    PLAN_CONFIRMED: { text: '已确认', color: 'success' },
    SPLITTING: { text: '拆分中', color: 'processing' },
    SUBPLANS_GENERATED: { text: '子方案已生成', color: 'success' },
    SUBPLAN_REVIEWING: { text: '子方案审查中', color: 'processing' },
    SUBPLANS_REVIEWED: { text: '子方案已审查', color: 'success' },
    SUBPLANS_CONFIRMED: { text: '子方案已确认', color: 'success' },
    TRANSMITTING: { text: '传输中', color: 'processing' },
    TRANSMITTED: { text: '已传输', color: 'success' },
  };

  const currentStatus = project ? statusLabel[project.status] || { text: project.status, color: 'default' } : null;

  return (
    <Header
      style={{
        background: '#fff',
        borderBottom: '1px solid #e6e9ee',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 22px',
        height: 56,
        lineHeight: '56px',
        boxShadow: '0 1px 4px rgba(0,0,0,0.05)',
      }}
    >
      <Space size="middle">
        <Space size={6}>
          <ThunderboltFilled style={{ fontSize: 20, color: '#2f6bbf' }} />
          <Text strong style={{ fontSize: 18, color: '#2f6bbf' }}>
            AI Designer
          </Text>
        </Space>
        {project && (
          <Select
            value={project.id}
            style={{ width: 220 }}
            size="small"
            placeholder="选择项目"
          >
            <Select.Option value={project.id}>{project.projectName}</Select.Option>
          </Select>
        )}
        {currentStatus && (
          <Badge
            status={currentStatus.color as 'default' | 'processing' | 'success'}
            text={currentStatus.text}
          />
        )}
        {isStreaming && (
          <Text type="secondary" style={{ fontSize: 12 }}>
            ⚡ LLM 响应中...
          </Text>
        )}
      </Space>

      <Space>
        <Tooltip title="一键载入「订单管理」完整示例(含模块、主方案、5 个已审子方案),可直接传输到 Part B 演示端到端流程">
          <Button
            icon={<RocketOutlined />}
            size="small"
            type="dashed"
            onClick={handleLoadDemo}
            style={{ borderColor: '#fa8c16', color: '#fa8c16' }}
          >
            载入示例
          </Button>
        </Tooltip>
        <Button icon={<PlusOutlined />} size="small" onClick={onNewProject}>
          新建
        </Button>
        <Button icon={<SaveOutlined />} size="small" onClick={onSave} disabled={!project}>
          保存
        </Button>
        <Button icon={<ExportOutlined />} size="small" onClick={onExport} disabled={!project}>
          导出
        </Button>
        <Tooltip title="配置 LLM (url / token / model),同步到 BFF 与 Part B">
          <Button icon={<SettingOutlined />} size="small" onClick={() => setLlmModalOpen(true)}>
            配置
          </Button>
        </Tooltip>
      </Space>
      <LlmConfigModal open={llmModalOpen} onClose={() => setLlmModalOpen(false)} />
    </Header>
  );
};

export default TopBar;
