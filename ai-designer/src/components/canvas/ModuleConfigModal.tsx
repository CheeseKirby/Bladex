import React, { useEffect, useState } from 'react';
import { Modal, Input, Switch, Select, Form, Typography, message } from 'antd';
import { usePlanStore } from '../../store/planStore';
import type { DraggedModule, ModuleConfig, ModuleType } from '../../types/plan';
import FieldsEditor from './FieldsEditor';

const { Text } = Typography;

interface ModuleConfigModalProps {
  module: DraggedModule | null;
  open: boolean;
  onClose: () => void;
}

const MODULE_LABEL: Record<ModuleType, string> = {
  ENTITY: '数据模型',
  API: 'API接口',
  PAGE: '前端页面',
  FLOW: '工作流',
  JOB: '定时任务',
  FEIGN: '远程调用',
  EXCEL: 'Excel导入导出',
  CONFIG: 'Nacos配置',
};

/**
 * 模块配置弹窗。
 *
 * 按模块类型渲染不同的配置字段,保存时调用 store 的 updateModuleConfig
 * (按 id 更新 canvasModules 与 project.modules 的 config,不可变替换)。
 */
const ModuleConfigModal: React.FC<ModuleConfigModalProps> = ({ module, open, onClose }) => {
  const updateModuleConfig = usePlanStore((s) => s.updateModuleConfig);

  // 本地草稿 — 打开时从 module.config 拷贝,保存时整体回写
  const [draft, setDraft] = useState<ModuleConfig>({});

  useEffect(() => {
    if (module) {
      setDraft({ ...(module.config || {}) });
    }
  }, [module]);

  if (!module) return null;

  const set = <K extends keyof ModuleConfig>(key: K, value: ModuleConfig[K]) => {
    setDraft((d) => ({ ...d, [key]: value }));
  };

  const handleSave = () => {
    updateModuleConfig(module.id, draft);
    message.success('模块配置已保存');
    onClose();
  };

  return (
    <Modal
      title={
        <span>
          {module.icon} 配置 {MODULE_LABEL[module.type]} 模块
        </span>
      }
      open={open}
      onOk={handleSave}
      onCancel={onClose}
      okText="保存"
      cancelText="取消"
      destroyOnHidden
      width={520}
    >
      <Form layout="vertical" style={{ marginTop: 12 }}>
        {module.type === 'ENTITY' && (
          <>
            <Form.Item label="表名 (snake_case)">
              <Input
                placeholder="blade_order"
                value={draft.tableName || ''}
                onChange={(e) => set('tableName', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="模块名 (小写,如 order)">
              <Input
                placeholder="order"
                value={draft.moduleName || ''}
                onChange={(e) => set('moduleName', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="实体名 (PascalCase,如 Order)">
              <Input
                placeholder="Order"
                value={draft.entityName || ''}
                onChange={(e) => set('entityName', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="选项">
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <SwitchRow
                  label="继承 BaseEntity"
                  checked={draft.extendBaseEntity ?? true}
                  onChange={(v) => set('extendBaseEntity', v)}
                />
                <SwitchRow label="需要 VO" checked={draft.needVO ?? true} onChange={(v) => set('needVO', v)} />
                <SwitchRow label="需要 Excel" checked={draft.needExcel ?? false} onChange={(v) => set('needExcel', v)} />
              </div>
            </Form.Item>
            <Form.Item label="业务字段 (可选,留空则由 AI 推断)">
              <FieldsEditor
                value={draft.fields || []}
                onChange={(next) => set('fields', next)}
              />
              <Text type="secondary" style={{ fontSize: 11, marginTop: 4, display: 'block' }}>
                填入的字段会作为强约束传给 LLM, 生成的 Entity/VO/DDL 会严格使用这些字段名/类型/注释。
              </Text>
            </Form.Item>
          </>
        )}

        {module.type === 'API' && (
          <>
            <Form.Item label="路径前缀 (如 order)">
              <Input
                placeholder="order"
                value={draft.pathPrefix || ''}
                onChange={(e) => set('pathPrefix', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="选项">
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <SwitchRow label="需要鉴权" checked={draft.needAuth ?? true} onChange={(v) => set('needAuth', v)} />
                <SwitchRow label="需要日志" checked={draft.needLog ?? false} onChange={(v) => set('needLog', v)} />
              </div>
            </Form.Item>
          </>
        )}

        {module.type === 'PAGE' && (
          <>
            <Form.Item label="页面名称">
              <Input
                placeholder="订单列表"
                value={draft.pageName || ''}
                onChange={(e) => set('pageName', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="页面类型">
              <Select
                value={draft.pageType || 'list'}
                onChange={(v) => set('pageType', v)}
                options={[
                  { value: 'list', label: '列表' },
                  { value: 'form', label: '表单' },
                  { value: 'detail', label: '详情' },
                  { value: 'dashboard', label: '看板' },
                ]}
              />
            </Form.Item>
            <Form.Item label="选项">
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <SwitchRow label="需要搜索" checked={draft.needSearch ?? true} onChange={(v) => set('needSearch', v)} />
                <SwitchRow
                  label="需要分页"
                  checked={draft.needPagination ?? true}
                  onChange={(v) => set('needPagination', v)}
                />
              </div>
            </Form.Item>
          </>
        )}

        {module.type === 'FLOW' && (
          <>
            <Form.Item label="流程名称">
              <Input
                placeholder="order-approve"
                value={draft.processName || ''}
                onChange={(e) => set('processName', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="流程节点 (逗号分隔)">
              <Input
                placeholder="提交,审核,完成"
                value={(draft.nodes || []).join(',')}
                onChange={(e) =>
                  set('nodes', e.target.value ? e.target.value.split(',').map((s) => s.trim()).filter(Boolean) : [])
                }
              />
            </Form.Item>
            <SwitchRow label="需要表单" checked={draft.needForm ?? true} onChange={(v) => set('needForm', v)} />
          </>
        )}

        {module.type === 'JOB' && (
          <>
            <Form.Item label="任务名称">
              <Input
                placeholder="orderSyncJob"
                value={draft.jobName || ''}
                onChange={(e) => set('jobName', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="Cron 表达式">
              <Input
                placeholder="0 0/5 * * * ?"
                value={draft.cronExpression || ''}
                onChange={(e) => set('cronExpression', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="Handler 类名">
              <Input
                placeholder="OrderSyncJobHandler"
                value={draft.jobHandler || ''}
                onChange={(e) => set('jobHandler', e.target.value)}
              />
            </Form.Item>
          </>
        )}

        {module.type === 'FEIGN' && (
          <>
            <Form.Item label="目标服务名">
              <Input
                placeholder="blade-order-service"
                value={draft.targetService || ''}
                onChange={(e) => set('targetService', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="API 前缀">
              <Input
                placeholder="/order"
                value={draft.apiPrefix || ''}
                onChange={(e) => set('apiPrefix', e.target.value)}
              />
            </Form.Item>
          </>
        )}

        {module.type === 'EXCEL' && (
          <>
            <Form.Item label="关联实体名">
              <Input
                placeholder="Order"
                value={draft.entityName || ''}
                onChange={(e) => set('entityName', e.target.value)}
              />
            </Form.Item>
            <Form.Item label="选项">
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <SwitchRow label="需要导入" checked={draft.needImport ?? true} onChange={(v) => set('needImport', v)} />
                <SwitchRow label="需要导出" checked={draft.needExport ?? true} onChange={(v) => set('needExport', v)} />
                <SwitchRow
                  label="需要模板"
                  checked={draft.needTemplate ?? true}
                  onChange={(v) => set('needTemplate', v)}
                />
              </div>
            </Form.Item>
          </>
        )}

        {module.type === 'CONFIG' && (
          <Form.Item label="配置类型">
            <Select
              value={draft.configType || 'datasource'}
              onChange={(v) => set('configType', v)}
              options={[
                { value: 'datasource', label: '数据源' },
                { value: 'route', label: '路由' },
                { value: 'security', label: '安全' },
                { value: 'tenant', label: '租户' },
              ]}
            />
          </Form.Item>
        )}
      </Form>

      <Text type="secondary" style={{ fontSize: 11 }}>
        提示: 模块配置会作为上下文提供给 LLM,帮助生成更精准的方案。如不配置,LLM 会根据需求文字自行推断。
      </Text>
    </Modal>
  );
};

const SwitchRow: React.FC<{ label: string; checked: boolean; onChange: (v: boolean) => void }> = ({
  label,
  checked,
  onChange,
}) => (
  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
    <span style={{ fontSize: 13 }}>{label}</span>
    <Switch size="small" checked={checked} onChange={onChange} />
  </div>
);

export default ModuleConfigModal;
