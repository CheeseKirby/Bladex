import React, { useEffect, useState } from 'react';
import { Modal, Input, Space, message, Alert, Typography, Button, List } from 'antd';
import { FolderOutlined, ArrowUpOutlined } from '@ant-design/icons';
import axios from 'axios';

const { Text } = Typography;

/** 参考项目状态(后端 ReferenceProjectVO) */
interface ReferenceStatus {
  path: string | null;
  ready: boolean;
  totalFiles: number;
  indexedClasses: number;
  moduleCount: number;
  scannedAt: string | null;
  durationMillis: number;
  /** 项目适配摘要(版本/结构信息),扫描后展示 */
  adaptationSummary?: string | null;
}

/** 目录浏览结果(后端 BrowseResult) */
interface BrowseResult {
  current: string | null;
  parent: string | null;
  dirs: string[];
  accessible: boolean;
}

interface ReferenceProjectModalProps {
  open: boolean;
  onClose: () => void;
}

/**
 * 参考项目配置 Modal — 阶段2增强。
 *
 * <p>用户指定一个 BladeX 框架项目路径,REAL 模式生成代码时从该项目找同类代码,
 * 提取结构化摘要注入 prompt,让生成的新模块贴合现有风格。参考项目只读,不写入。
 */
const ReferenceProjectModal: React.FC<ReferenceProjectModalProps> = ({ open, onClose }) => {
  const [path, setPath] = useState('');
  const [status, setStatus] = useState<ReferenceStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [scanning, setScanning] = useState(false);
  // 浏览面板状态
  const [browsing, setBrowsing] = useState(false);
  const [browseResult, setBrowseResult] = useState<BrowseResult | null>(null);
  const [browseLoading, setBrowseLoading] = useState(false);

  // open 时加载当前状态
  useEffect(() => {
    if (open) {
      loadStatus();
    }
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const loadStatus = async () => {
    setLoading(true);
    try {
      const res = await axios.get('/api/transmission/reference', { timeout: 25_000 });
      const s = res.data?.data || res.data;
      setStatus(s);
      setPath(s?.path || '');
    } catch (err) {
      // 静默处理(可能 Part B 未启动)
      console.warn('加载参考项目状态失败', err);
    } finally {
      setLoading(false);
    }
  };

  const handleScan = async () => {
    if (!path.trim()) {
      message.warning('请输入参考项目路径');
      return;
    }
    setScanning(true);
    try {
      const res = await axios.post('/api/transmission/reference', { path: path.trim() }, { timeout: 70_000 });
      const s = res.data?.data || res.data;
      setStatus(s);
      if (s?.ready) {
        message.success(`参考项目已就绪: ${s.totalFiles} 个文件, ${s.moduleCount} 个模块`);
      } else {
        message.warning('参考项目扫描完成但未就绪,请检查路径');
      }
    } catch (err: unknown) {
      const msg = axios.isAxiosError(err)
        ? err.response?.data?.msg || err.message
        : (err as Error).message;
      message.error(`扫描失败: ${msg}`);
    } finally {
      setScanning(false);
    }
  };

  const handleClear = async () => {
    setScanning(true);
    try {
      const res = await axios.post('/api/transmission/reference', { path: null }, { timeout: 25_000 });
      setStatus(res.data?.data || res.data);
      setPath('');
      message.success('已取消参考项目');
    } catch (err: unknown) {
      const msg = axios.isAxiosError(err)
        ? err.response?.data?.msg || err.message
        : (err as Error).message;
      message.error(`取消失败: ${msg}`);
    } finally {
      setScanning(false);
    }
  };

  /** 打开浏览面板,初始列盘符或当前路径 */
  const openBrowser = async () => {
    setBrowsing(true);
    await loadBrowse(path.trim() || '');
  };

  /** 加载某目录的子目录列表 */
  const loadBrowse = async (dir: string) => {
    setBrowseLoading(true);
    try {
      const url = dir ? `/api/transmission/browse?path=${encodeURIComponent(dir)}` : '/api/transmission/browse';
      const res = await axios.get(url, { timeout: 25_000 });
      const r: BrowseResult = res.data?.data || res.data;
      setBrowseResult(r);
      if (!r.accessible) {
        message.warning('目录不可访问: ' + dir);
      }
    } catch (err: unknown) {
      const msg = axios.isAxiosError(err)
        ? err.response?.data?.msg || err.message
        : (err as Error).message;
      message.error(`浏览失败: ${msg}`);
      setBrowseResult(null);
    } finally {
      setBrowseLoading(false);
    }
  };

  /** 选定当前目录作为参考路径 */
  const pickCurrentDir = () => {
    if (browseResult?.current) {
      setPath(browseResult.current);
      setBrowsing(false);
      message.info('已选择路径,点"扫描并应用"生效');
    }
  };

  return (
    <Modal
      title="📁 参考项目"
      open={open}
      onCancel={onClose}
      width={640}
      footer={
        <Space>
          <Button onClick={onClose}>关闭</Button>
          {status?.ready && (
            <Button danger onClick={handleClear} loading={scanning}>取消参考</Button>
          )}
          <Button type="primary" onClick={handleScan} loading={scanning}>扫描并应用</Button>
        </Space>
      }
      destroyOnHidden
    >
      <Alert
        type="info"
        showIcon
        message="REAL 模式生成代码时,从参考项目找同类代码,提取结构化摘要注入 LLM,让生成的新模块贴合现有风格。"
        style={{ marginBottom: 16 }}
      />

      <div style={{ marginBottom: 8 }}>
        <Text strong>参考项目路径(BladeX 框架项目根目录)</Text>
      </div>
      <Space.Compact style={{ width: '100%' }}>
        <Input
          placeholder="如:E:/workspace/doc4test/houduan/blade_hgsjy(请填绝对路径)"
          value={path}
          onChange={(e) => setPath(e.target.value)}
          disabled={scanning}
          onPressEnter={handleScan}
        />
        <Button onClick={openBrowser} disabled={scanning} style={{ width: 80 }}>浏览</Button>
      </Space.Compact>
      <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
        填一个标准 BladeX 项目路径,生成代码时参考它的风格。留空或取消则不注入参考。
      </Text>

      {/* 目录浏览面板 */}
      {browsing && (
        <div style={{ border: '1px solid #d9d9d9', borderRadius: 4, marginTop: 12, padding: 8 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <Text strong style={{ fontSize: 13 }}>
              当前: {browseResult?.current || '(盘符列表)'}
            </Text>
            <Space size="small">
              {browseResult?.parent !== undefined && browseResult.parent !== null && (
                <Button size="small" icon={<ArrowUpOutlined />} onClick={() => loadBrowse(browseResult.parent!)}>
                  上一级
                </Button>
              )}
              {browseResult?.current && (
                <Button size="small" type="primary" onClick={pickCurrentDir}>选择此目录</Button>
              )}
              <Button size="small" onClick={() => setBrowsing(false)}>取消</Button>
            </Space>
          </div>
          <List
            size="small"
            bordered
            loading={browseLoading}
            dataSource={browseResult?.dirs || []}
            locale={{ emptyText: browseLoading ? '加载中...' : '无子目录' }}
            renderItem={(name) => (
              <List.Item
                style={{ cursor: 'pointer' }}
                onClick={() => loadBrowse((browseResult?.current || '') + (browseResult?.current?.endsWith('/') ? '' : '/') + name)}
              >
                <Space>
                  <FolderOutlined />
                  <Text>{name}</Text>
                </Space>
              </List.Item>
            )}
            style={{ maxHeight: 240, overflow: 'auto' }}
          />
        </div>
      )}

      {/* 状态显示 */}
      <div style={{ background: '#fafafa', padding: 12, borderRadius: 4, marginTop: 16 }}>
        <Text strong>当前状态</Text>
        <div style={{ marginTop: 8 }}>
          {loading ? (
            <Text type="secondary">加载中...</Text>
          ) : status?.ready ? (
            <>
              <Text type="success">✓ 已就绪</Text>
              <div style={{ marginTop: 4, fontSize: 12 }}>
                <Text code>路径: {status.path}</Text>
                <br />
                <Text code>文件: {status.totalFiles} | 索引类: {status.indexedClasses} | 模块: {status.moduleCount}</Text>
                <br />
                <Text type="secondary" style={{ fontSize: 11 }}>
                  扫描于 {status.scannedAt}({status.durationMillis}ms)
                </Text>
              </div>
              {status.adaptationSummary && (
                <div style={{
                  marginTop: 8, padding: 8, background: '#f6f8fa',
                  borderRadius: 4, fontSize: 11, whiteSpace: 'pre-wrap',
                  maxHeight: 200, overflow: 'auto', fontFamily: 'monospace',
                }}>
                  <Text strong style={{ fontSize: 12 }}>📋 依赖版本适配</Text>
                  {'\n'}
                  {status.adaptationSummary}
                </div>
              )}
            </>
          ) : status?.path ? (
            <Text type="warning">已设置路径但未就绪: {status.path}</Text>
          ) : (
            <Text type="secondary">未设置(REAL 模式生成时不注入参考)</Text>
          )}
        </div>
      </div>
    </Modal>
  );
};

export default ReferenceProjectModal;
