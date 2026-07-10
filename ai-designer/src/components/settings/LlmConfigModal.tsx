import React, { useEffect, useState } from 'react';
import { Modal, Form, Input, InputNumber, Switch, Space, message, Alert, Typography, Divider, Button } from 'antd';
import axios from 'axios';

const { Text } = Typography;

interface LlmConfigView {
  baseUrl: string;
  model: string;
  authToken: string;     // 已脱敏 (****)
  apiKey: string;        // 已脱敏 (****)
  anthropicVersion: string;
  maxTokens: number;
  hasAuthToken: boolean;
  hasApiKey: boolean;
}

interface LlmConfigPatch {
  baseUrl?: string;
  model?: string;
  authToken?: string;
  apiKey?: string;
  anthropicVersion?: string;
  maxTokens?: number;
}

interface LlmConfigModalProps {
  open: boolean;
  onClose: () => void;
}

const COMMON_PRESETS: { label: string; baseUrl: string; model: string }[] = [
  { label: 'Anthropic 官方', baseUrl: 'https://api.anthropic.com', model: 'claude-sonnet-4-5' },
  { label: '火山方舟 (Claude)', baseUrl: 'https://ark.cn-beijing.volces.com/api/coding', model: 'claude-sonnet-4-5' },
  { label: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/anthropic', model: 'glm-5.1' },
  { label: 'DeepSeek (兼容)', baseUrl: 'https://api.deepseek.com/anthropic', model: 'deepseek-chat' },
];

const LlmConfigModal: React.FC<LlmConfigModalProps> = ({ open, onClose }) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [syncToB, setSyncToB] = useState(true);
  const [bffStatus, setBffStatus] = useState<LlmConfigView | null>(null);
  const [partBStatus, setPartBStatus] = useState<LlmConfigView | null>(null);

  const loadConfigs = async () => {
    setLoading(true);
    try {
      const [bff, partB] = await Promise.allSettled([
        axios.get('/api/config/llm').then((r) => r.data),
        // 通过 BFF 代理读 Part B,避免 CORS
        axios.get('/api/transmission/partb-config').then((r) => r.data).catch(() => null),
      ]);
      if (bff.status === 'fulfilled' && bff.value.success) {
        const data: LlmConfigView = bff.value.data;
        setBffStatus(data);
        form.setFieldsValue({
          baseUrl: data.baseUrl,
          model: data.model,
          anthropicVersion: data.anthropicVersion,
          maxTokens: data.maxTokens,
          authToken: data.hasAuthToken ? data.authToken : '',
          apiKey: data.hasApiKey ? data.apiKey : '',
        });
      }
      if (partB.status === 'fulfilled' && partB.value?.success) {
        setPartBStatus(partB.value.data);
      } else {
        setPartBStatus(null);
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) loadConfigs();
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      const patch: LlmConfigPatch = {
        baseUrl: values.baseUrl,
        model: values.model,
        anthropicVersion: values.anthropicVersion,
        maxTokens: values.maxTokens,
      };
      // 鉴权字段: 脱敏字符串含 '*' 视为未修改,不发送;明文(无 *)或空串才发送
      // 注意: 不能用 startsWith('*'),因为脱敏格式是 "前缀****后缀" (例 "ark-63****cee4")
      const isMasked = (v: string | undefined): boolean => !!v && v.includes('*');
      if (values.authToken === '') {
        patch.authToken = '';
      } else if (values.authToken && !isMasked(values.authToken)) {
        patch.authToken = values.authToken;
      }
      if (values.apiKey === '') {
        patch.apiKey = '';
      } else if (values.apiKey && !isMasked(values.apiKey)) {
        patch.apiKey = values.apiKey;
      }

      // 第一步: 保存到 BFF (本地) — 这是核心动作, 失败才是真"保存失败"
      await axios.put('/api/config/llm', patch);

      // 第二步: 同步到 Part B — 用独立 try/catch, 失败不影响本地已保存的结果
      // (Part B 可能未启动, 本地配置已生效, 后续 Part B 启动时会从环境变量读取)
      if (syncToB) {
        try {
          const sync = await axios.post('/api/config/llm/sync-to-partb', null, { timeout: 15000 });
          if (sync.data?.success) {
            message.success('BFF 与 Part B 配置均已更新');
          } else {
            message.warning(
              `BFF 已保存,但 Part B 同步失败: ${sync.data?.msg || '未知'}。Part B 启动后会从自身环境变量读取,不影响 BFF 使用。`
            );
          }
        } catch (syncErr) {
          const sm = axios.isAxiosError(syncErr) ? syncErr.response?.data?.msg || syncErr.message : (syncErr as Error).message;
          message.warning(
            `BFF 已保存,但 Part B 同步失败 (${sm})。Part B 可能未启动,启动后会从环境变量读取,不影响 BFF 使用。`
          );
        }
      } else {
        message.success('BFF 配置已更新');
      }
      loadConfigs();
    } catch (err) {
      // 只有本地 PUT 失败才报"保存失败"
      const msg = axios.isAxiosError(err) ? err.response?.data?.msg || err.response?.data?.error || err.message : (err as Error).message;
      message.error(`保存失败: ${msg}`);
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setTesting(true);
    try {
      const res = await axios.post('/api/llm/review-plan', {
        planContent: '# 测试方案\n创建一个 Order 实体',
        stage: 'master',
      });
      if (res.data?.success) {
        message.success('LLM 连通测试成功 ✓');
      } else {
        message.warning(`LLM 测试响应异常: ${JSON.stringify(res.data).slice(0, 200)}`);
      }
    } catch (err) {
      const msg = axios.isAxiosError(err) ? err.response?.data?.error || err.message : (err as Error).message;
      message.error(`LLM 测试失败: ${msg}`);
    } finally {
      setTesting(false);
    }
  };

  const applyPreset = (preset: (typeof COMMON_PRESETS)[number]) => {
    form.setFieldsValue({ baseUrl: preset.baseUrl, model: preset.model });
  };

  return (
    <Modal
      title="⚙️ LLM 配置"
      open={open}
      onCancel={onClose}
      width={720}
      footer={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button onClick={handleTest} loading={testing}>测试连通</Button>
          <Button type="primary" onClick={handleSave} loading={saving}>保存并应用</Button>
        </Space>
      }
      destroyOnClose
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="运行时配置 — 改动立即对后续 LLM 调用生效,但进程重启会回退到启动时的环境变量"
        description={
          <span style={{ fontSize: 12 }}>
            鉴权优先级: <Text code>authToken</Text>(Bearer, ccswitch 风格) → <Text code>apiKey</Text>(x-api-key, Anthropic 直连)
          </span>
        }
      />

      <div style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ fontSize: 12, marginRight: 8 }}>快速预设:</Text>
        {COMMON_PRESETS.map((p) => (
          <Button key={p.label} size="small" style={{ marginRight: 6 }} onClick={() => applyPreset(p)}>
            {p.label}
          </Button>
        ))}
      </div>

      <Form form={form} layout="vertical" disabled={loading || saving}>
        <Form.Item
          name="baseUrl"
          label="API Base URL"
          rules={[
            { required: true, message: '请填写 base URL' },
            { pattern: /^https?:\/\//, message: 'URL 必须以 http:// 或 https:// 开头' },
          ]}
        >
          <Input placeholder="https://api.anthropic.com 或 ccswitch 中转网关地址" />
        </Form.Item>

        <Form.Item
          name="model"
          label="模型名称"
          rules={[{ required: true, message: '请填写模型名' }]}
          extra="必须是当前 base URL 服务商支持的模型。例: glm-5.1(智谱) / claude-sonnet-4-5(Anthropic、火山方舟 coding plan) / deepseek-chat"
        >
          <Input placeholder="glm-5.1 / claude-sonnet-4-5 / claude-haiku-4-5 / ..." />
        </Form.Item>

        <Form.Item
          name="authToken"
          label="Auth Token (Bearer, 推荐 — ccswitch / claude-code 风格)"
          extra="留空清除,显示 ****** 表示已配置且未修改"
        >
          <Input.Password autoComplete="off" placeholder="sk-... 或 ccswitch 网关 token" />
        </Form.Item>

        <Form.Item
          name="apiKey"
          label="API Key (x-api-key, Anthropic 官方直连备选)"
          extra="如已填 Auth Token 此处可留空"
        >
          <Input.Password autoComplete="off" placeholder="sk-ant-..." />
        </Form.Item>

        <Space style={{ width: '100%' }} size="middle">
          <Form.Item name="anthropicVersion" label="Anthropic-Version" style={{ width: 200 }}>
            <Input placeholder="2023-06-01" />
          </Form.Item>
          <Form.Item name="maxTokens" label="max_tokens">
            <InputNumber min={256} max={64000} step={1024} style={{ width: 140 }} />
          </Form.Item>
        </Space>

        <Divider style={{ margin: '8px 0' }} />

        <Form.Item label="同时同步到 Part B (8111)" tooltip="保存时通过 BFF 转发 PUT /api/config/llm 到 Part B,让 Java 端也使用同一组配置">
          <Switch checked={syncToB} onChange={setSyncToB} />
        </Form.Item>
      </Form>

      <div style={{ background: '#fafafa', padding: 10, borderRadius: 4, fontSize: 12 }}>
        <Text strong>当前状态</Text>
        <div style={{ marginTop: 6 }}>
          <Text type="secondary">BFF (3004):</Text>{' '}
          {bffStatus ? (
            <Text code>
              {bffStatus.hasAuthToken ? 'bearer' : bffStatus.hasApiKey ? 'x-api-key' : 'NONE'} | {bffStatus.model} | {bffStatus.baseUrl}
            </Text>
          ) : (
            <Text type="warning">未连接</Text>
          )}
        </div>
        <div style={{ marginTop: 4 }}>
          <Text type="secondary">Part B (8111):</Text>{' '}
          {partBStatus ? (
            <Text code>
              {partBStatus.hasAuthToken ? 'bearer' : partBStatus.hasApiKey ? 'x-api-key' : 'NONE'} | {partBStatus.model} | {partBStatus.baseUrl}
            </Text>
          ) : (
            <Text type="warning">未连接(可能未启动或未同步)</Text>
          )}
        </div>
      </div>
    </Modal>
  );
};

export default LlmConfigModal;
