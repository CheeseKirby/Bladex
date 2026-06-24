import React, { useState, useRef, useCallback } from 'react';
import { Input, Button, Space, Tag, message } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import { generatePlanStream } from '../../services/api';
import type { SSEMessage, DraggedModule } from '../../types/plan';
import RequirementStatusBar from './RequirementStatusBar';

const { TextArea } = Input;

const QUICK_COMMANDS = [
  { label: '新建CRUD模块', prompt: '我需要一个标准的CRUD模块，支持增删改查和分页列表' },
  { label: '添加Excel导出', prompt: '请为这个模块添加Excel导入导出功能' },
  { label: '添加工作流', prompt: '请添加审批工作流' },
];

interface ChatInputProps {
  disabled?: boolean;
}

const ChatInput: React.FC<ChatInputProps> = ({ disabled }) => {
  const [input, setInput] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  const streamingContent = usePlanStore((s) => s.streamingContent);
  const setStreamingContent = usePlanStore((s) => s.setStreamingContent);
  const appendStreamingChunk = usePlanStore((s) => s.appendStreamingChunk);
  const setIsStreaming = usePlanStore((s) => s.setIsStreaming);
  const setProjectStatus = usePlanStore((s) => s.setProjectStatus);
  const setMasterPlan = usePlanStore((s) => s.setMasterPlan);
  const setReviewResult = usePlanStore((s) => s.setReviewResult);
  const canvasModules = usePlanStore((s) => s.canvasModules);
  const addModuleToCanvas = usePlanStore((s) => s.addModuleToCanvas);
  const project = usePlanStore((s) => s.project);
  const textAreaRef = useRef<HTMLTextAreaElement>(null);

  /** 推荐模块时批量添加到画布 */
  const handleAddModules = useCallback(
    (modules: DraggedModule[]) => {
      for (const m of modules) {
        addModuleToCanvas(m);
      }
    },
    [addModuleToCanvas]
  );

  const handleSend = async () => {
    if (!input.trim() || !project) return;

    setStreamingContent('');
    setIsGenerating(true);
    setIsStreaming(true);
    setProjectStatus('ANALYZING');

    const planContentBuffer: string[] = [];

    const handleMessage = (msg: SSEMessage) => {
      switch (msg.type) {
        case 'progress': {
          if (msg.stage) {
            const stageMap: Record<string, string> = {
              analyzing: 'ANALYZING',
              planning: 'PLANNING',
              reviewing: 'REVIEWING',
              splitting: 'SPLITTING',
              'subplan-reviewing': 'SUBPLAN_REVIEWING',
            };
            const status = stageMap[msg.stage];
            if (status) setProjectStatus(status as Parameters<typeof setProjectStatus>[0]);
          }
          break;
        }
        case 'content': {
          if (msg.chunk) {
            appendStreamingChunk(msg.chunk);
            planContentBuffer.push(msg.chunk);
          }
          break;
        }
        case 'complete': {
          const planContent = planContentBuffer.join('');
          if (planContent) {
            setMasterPlan({
              id: `plan_${Date.now()}`,
              projectId: project.id,
              version: 1,
              planContent,
              status: 'PLAN_GENERATED',
            });
            setProjectStatus('PLAN_GENERATED');
          }
          break;
        }
        case 'error': {
          // 让用户感知到错误,而非静默挂死
          message.error(`方案生成失败: ${msg.error || '未知错误'}`);
          setProjectStatus('DRAFT');
          break;
        }
      }
    };

    try {
      await generatePlanStream(
        {
          userInput: input,
          modules: canvasModules,
          projectId: project.id,
        },
        handleMessage,
        (err) => {
          console.error('流式错误:', err);
          message.error(`生成失败: ${err.message}`);
          setProjectStatus('DRAFT');
          setIsGenerating(false);
          setIsStreaming(false);
        },
        () => {
          setIsGenerating(false);
          setIsStreaming(false);
        }
      );
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      console.error('请求失败:', err);
      message.error(`请求失败: ${msg}`);
      setIsGenerating(false);
      setIsStreaming(false);
    }

    setInput('');
    textAreaRef.current?.focus();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleQuickCommand = (prompt: string) => {
    setInput(prompt);
    textAreaRef.current?.focus();
  };

  return (
    <div>
      {/* 需求状态条 — 实时显示配置完整度 + 双向补齐入口 (阶段二新增) */}
      {project && (
        <RequirementStatusBar
          requirement={input}
          onRequirementChange={setInput}
          onAddModules={handleAddModules}
        />
      )}

      {/* 快捷指令 */}
      <div style={{ marginBottom: 10 }}>
        <Space size={6} wrap>
          {QUICK_COMMANDS.map((cmd) => (
            <Tag
              key={cmd.label}
              style={{ cursor: 'pointer', fontSize: 14, padding: '3px 12px' }}
              color="blue"
              onClick={() => handleQuickCommand(cmd.prompt)}
            >
              {cmd.label}
            </Tag>
          ))}
        </Space>
      </div>

      {/* 输入区域 */}
      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end' }}>
        <TextArea
          ref={textAreaRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={disabled ? '请先创建项目...' : '请描述您的开发需求... (Enter发送, Shift+Enter换行)'}
          disabled={disabled || isGenerating}
          autoSize={{ minRows: 2, maxRows: 8 }}
          style={{ flex: 1, fontSize: 15 }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          onClick={handleSend}
          loading={isGenerating}
          disabled={disabled || !input.trim()}
          style={{ height: 40 }}
        >
          发送
        </Button>
      </div>

      {/* 流式内容预览 — 可上下滚动查看完整生成内容 */}
      {isGenerating && streamingContent && (
        <div
          style={{
            marginTop: 10,
            padding: 10,
            background: '#f6ffed',
            border: '1px solid #d9f7be',
            borderRadius: 6,
            maxHeight: 220,
            overflowY: 'auto',
            fontSize: 13,
            lineHeight: 1.7,
            color: '#555',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {streamingContent}
        </div>
      )}
    </div>
  );
};

export default ChatInput;
