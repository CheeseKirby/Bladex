import React, { useState, useCallback } from 'react';
import { Space, Tag, Button, Tooltip, message } from 'antd';
import { BulbOutlined, ThunderboltOutlined, CheckCircleFilled, WarningFilled, CloseCircleFilled, HighlightOutlined } from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import { enrichRequirement, suggestModules, completeOneShot } from '../../services/api';
import type { DraggedModule, ModuleType } from '../../types/plan';

interface RequirementStatusBarProps {
  requirement: string;
  onRequirementChange: (text: string) => void;
  onAddModules: (modules: DraggedModule[]) => void;
}

type CompletenessLevel = 'full' | 'medium' | 'low';

const MODULE_COLORS: Record<string, string> = {
  ENTITY: '#1890ff', API: '#52c41a', PAGE: '#fa8c16',
  FLOW: '#722ed1', JOB: '#eb2f96', FEIGN: '#13c2c2',
  EXCEL: '#2f54eb', CONFIG: '#595959',
};

/**
 * 需求状态条。
 *
 * 主操作: 「✨ 一键完备」— 固定顺序: 推荐模块 → 补齐需求, 解决先后顺序歧义。
 * 次操作: 「补模块」「补需求」— 独立调用, 给高级用户按需使用。
 */
const RequirementStatusBar: React.FC<RequirementStatusBarProps> = ({
  requirement, onRequirementChange, onAddModules,
}) => {
  const canvasModules = usePlanStore((s) => s.canvasModules);

  const [busy, setBusy] = useState<'none' | 'complete' | 'enrich' | 'suggest'>('none');

  // ─── 统计完整度 ───
  const hasEntity = canvasModules.some((m) => m.type === 'ENTITY' && m.config?.entityName);
  const hasTableName = canvasModules.some((m) => m.type === 'ENTITY' && m.config?.tableName);
  const hasModuleName = canvasModules.some((m) => m.type === 'ENTITY' && m.config?.moduleName);
  const hasFields = canvasModules.some((m) => m.type === 'ENTITY' && (m.config?.fields?.length ?? 0) > 0);
  const hasApi = canvasModules.some((m) => m.type === 'API' && m.config?.pathPrefix);
  const reqLen = requirement.trim().length;
  const totalModules = canvasModules.length;
  const configuredCount = [hasEntity, hasTableName, hasModuleName, hasFields, hasApi].filter(Boolean).length;
  const keyNamesConfigured = hasEntity && hasTableName && hasModuleName;

  let level: CompletenessLevel;
  if (totalModules >= 2 && keyNamesConfigured && reqLen >= 20) level = 'full';
  else if (totalModules >= 1 && (keyNamesConfigured || reqLen >= 10)) level = 'medium';
  else level = 'low';

  const levelIcon = (lv: CompletenessLevel) => {
    switch (lv) {
      case 'full': return <CheckCircleFilled style={{ color: '#52c41a' }} />;
      case 'medium': return <WarningFilled style={{ color: '#faad14' }} />;
      case 'low': return <CloseCircleFilled style={{ color: '#ff4d4f' }} />;
    }
  };
  const levelLabel = (lv: CompletenessLevel) => {
    switch (lv) {
      case 'full': return '已就绪,可生成方案';
      case 'medium': return '中等,建议继续完善';
      case 'low': return '强烈建议先拖模块或输入需求';
    }
  };

  // ─── 一键完备 (主操作) ───
  const handleComplete = useCallback(async () => {
    setBusy('complete');
    try {
      const res = await completeOneShot(requirement, canvasModules);
      if (!res.success) { message.warning(res.msg || '操作失败'); return; }
      const { suggestions, enriched, reasoning } = res.data || {};
      if (Array.isArray(suggestions) && suggestions.length > 0) {
        const modules: DraggedModule[] = suggestions.map(
          (s: { type: string; name: string; icon: string; config: Record<string, unknown> }) => ({
            id: `${s.type}_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
            type: s.type as ModuleType,
            name: s.name || s.type,
            icon: s.icon || '📦',
            color: MODULE_COLORS[s.type] || '#666',
            config: s.config || {},
          })
        );
        onAddModules(modules);
      }
      if (enriched && enriched.length > requirement.length + 10) {
        onRequirementChange(enriched);
      }
      if (reasoning) {
        message.info(`推荐理由: ${reasoning}`, 3);
      }
      message.success('已自动推荐模块 + 补齐需求,请确认后发送');
    } catch (err) {
      message.error(`操作失败: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      setBusy('none');
    }
  }, [requirement, canvasModules, onRequirementChange, onAddModules]);

  // ─── 仅推荐模块 ───
  const handleSuggest = useCallback(async () => {
    setBusy('suggest');
    try {
      const res = await suggestModules(requirement);
      if (res.success && Array.isArray(res.data?.suggestions)) {
        const modules: DraggedModule[] = res.data.suggestions.map(
          (s: { type: string; name: string; icon: string; config: Record<string, unknown> }) => ({
            id: `${s.type}_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
            type: s.type as ModuleType, name: s.name || s.type, icon: s.icon || '📦',
            color: MODULE_COLORS[s.type] || '#666', config: s.config || {},
          })
        );
        onAddModules(modules);
        message.success(`已推荐 ${modules.length} 个模块`);
      } else {
        message.warning('未识别到模块建议');
      }
    } catch (err) {
      message.error(`推荐失败: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      setBusy('none');
    }
  }, [requirement, onAddModules]);

  // ─── 仅补齐需求 ───
  const handleEnrich = useCallback(async () => {
    if (requirement.length > 100) { message.info('需求已较完整,无需补齐'); return; }
    setBusy('enrich');
    try {
      const res = await enrichRequirement(requirement, canvasModules);
      if (res.success && res.data?.enriched) {
        onRequirementChange(res.data.enriched);
        message.success('需求已自动补齐');
      } else {
        message.warning('补齐失败');
      }
    } catch (err) {
      message.error(`补齐失败: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      setBusy('none');
    }
  }, [requirement, canvasModules, onRequirementChange]);

  return (
    <div style={{
      marginBottom: 10, padding: '8px 12px', borderRadius: 8,
      background: level === 'full' ? '#f1faec' : level === 'medium' ? '#fff9e8' : '#fef0ee',
      border: `1px solid ${level === 'full' ? '#cfe8c1' : level === 'medium' ? '#ffe9a8' : '#f6c9c3'}`,
      fontSize: 13,
    }}>
      {/* 第一行: 完整度色标 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        {levelIcon(level)}
        <span style={{ fontWeight: 600, fontSize: 14 }}>{levelLabel(level)}</span>
      </div>

      {/* 第二行: 信息标签 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, alignItems: 'center', marginBottom: 8 }}>
        <Tag style={{ fontSize: 12, lineHeight: '18px', margin: 0, padding: '0 8px' }}>📦 {totalModules} 模块</Tag>
        <Tag style={{ fontSize: 12, lineHeight: '18px', margin: 0, padding: '0 8px' }}>⚙️ {configuredCount} 项已配</Tag>
        <Tag style={{ fontSize: 12, lineHeight: '18px', margin: 0, padding: '0 8px' }}>✍️ {reqLen} 字</Tag>
        {!keyNamesConfigured && totalModules > 0 && (
          <Tag color="orange" style={{ fontSize: 12, lineHeight: '18px', margin: 0, padding: '0 8px' }}>缺关键命名</Tag>
        )}
      </div>

      {/* 第三行: 主操作 + 次操作 */}
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <Button
          size="middle" type="primary"
          icon={<HighlightOutlined />}
          onClick={handleComplete}
          loading={busy === 'complete'}
          disabled={requirement.trim().length < 2}
          style={{ fontSize: 14, height: 34 }}
        >
          ✨ 一键完备
        </Button>
        <Button
          size="middle"
          icon={<ThunderboltOutlined />}
          onClick={handleSuggest}
          loading={busy === 'suggest'}
          disabled={requirement.trim().length < 2 || busy !== 'none'}
          style={{ fontSize: 13, height: 34 }}
        >
          补模块
        </Button>
        <Button
          size="middle"
          icon={<BulbOutlined />}
          onClick={handleEnrich}
          loading={busy === 'enrich'}
          disabled={requirement.length > 100 || busy !== 'none'}
          style={{ fontSize: 13, height: 34 }}
        >
          补需求
        </Button>
      </div>
    </div>
  );
};

export default RequirementStatusBar;