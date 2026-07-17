import React, { useState } from 'react';
import { Empty, List, Tag, Typography, Button, Modal, Spin, message, Space, Tooltip } from 'antd';
import {
  FileTextOutlined,
  FileMarkdownOutlined,
  ReloadOutlined,
  CopyOutlined,
  DownloadOutlined,
} from '@ant-design/icons';
import { usePlanStore } from '../../store/planStore';
import { getPartBFileDetail } from '../../services/api';
import { usePartBStatusPoll } from '../../hooks/usePartBStatusPoll';
import type { GeneratedFileDetail, GeneratedFileSummary } from '../../types/api';

const { Text } = Typography;

function iconForExt(ext?: string) {
  switch ((ext || '').toLowerCase()) {
    case 'java':
      return <FileTextOutlined style={{ color: '#1677ff' }} />;
    case 'sql':
      return <FileTextOutlined style={{ color: '#fa8c16' }} />;
    case 'md':
      return <FileMarkdownOutlined style={{ color: '#722ed1' }} />;
    case 'xml':
      return <FileTextOutlined style={{ color: '#13c2c2' }} />;
    case 'yml':
    case 'yaml':
      return <FileTextOutlined style={{ color: '#52c41a' }} />;
    default:
      return <FileTextOutlined />;
  }
}

function actionTag(action?: string) {
  switch (action) {
    case 'CREATED':
      return <Tag color="green">已写入</Tag>;
    case 'MODIFIED':
      return <Tag color="blue">已修改</Tag>;
    case 'GENERATED_NOT_WRITTEN':
      return (
        <Tooltip title="目标项目根目录不存在,文件未写入磁盘,但 LLM 已生成完整内容供查看/复制">
          <Tag color="orange">仅生成</Tag>
        </Tooltip>
      );
    case 'SKIPPED':
      return <Tag>跳过</Tag>;
    default:
      return action ? <Tag>{action}</Tag> : null;
  }
}

const GeneratedFilesView: React.FC = () => {
  const receptionId = usePlanStore((s) => s.receptionId);
  const files = usePlanStore((s) => s.generatedFiles);
  const { refreshFiles } = usePartBStatusPoll();
  const [refreshing, setRefreshing] = useState(false);
  const [currentDetail, setCurrentDetail] = useState<GeneratedFileDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [open, setOpen] = useState(false);

  if (!receptionId) {
    return (
      <div style={{ padding: 16 }}>
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="尚未传输方案到 Part B"
        />
        <Text type="secondary" style={{ fontSize: 11, display: 'block', textAlign: 'center' }}>
          传输后此处显示 Part B 生成的所有代码文件
        </Text>
      </div>
    );
  }

  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      const refreshed = await refreshFiles();
      if (!refreshed) message.error('生成文件列表加载失败，请检查 Part B 日志或稍后重试');
    } finally {
      setRefreshing(false);
    }
  };

  const handleOpen = async (file: GeneratedFileSummary) => {
    setOpen(true);
    setCurrentDetail(null);
    setDetailLoading(true);
    try {
      const res = await getPartBFileDetail(file.id);
      if (res.success && res.data) {
        setCurrentDetail(res.data);
      } else {
        message.error('文件加载失败');
      }
    } finally {
      setDetailLoading(false);
    }
  };

  const handleCopy = async () => {
    if (!currentDetail) return;
    try {
      await navigator.clipboard.writeText(currentDetail.content);
      message.success('已复制完整内容');
    } catch {
      message.error('复制失败,请手动选择');
    }
  };

  const handleDownload = () => {
    if (!currentDetail) return;
    const blob = new Blob([currentDetail.content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = currentDetail.fileName || `file-${currentDetail.id}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // 按子方案分组
  const grouped = files.reduce<Record<string, GeneratedFileSummary[]>>((acc, f) => {
    const key = f.subPlanTitle || `子方案 #${f.subPlanId}`;
    if (!acc[key]) acc[key] = [];
    acc[key].push(f);
    return acc;
  }, {});

  const groupKeys = Object.keys(grouped);

  return (
    <div style={{ padding: '0 8px' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }}>
        <Text style={{ fontSize: 12 }}>
          共 <Text strong>{files.length}</Text> 个文件
        </Text>
        <Button size="small" icon={<ReloadOutlined />} loading={refreshing} onClick={handleRefresh}>
          刷新
        </Button>
      </Space>

      {files.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="Part B 尚未生成任何文件"
        />
      ) : (
        groupKeys.map((title) => (
          <div key={title} style={{ marginBottom: 12 }}>
            <Text strong style={{ fontSize: 12, color: '#666' }}>
              📂 {title}{' '}
              <Text type="secondary" style={{ fontSize: 11 }}>
                ({grouped[title].length})
              </Text>
            </Text>
            <List
              size="small"
              dataSource={grouped[title]}
              renderItem={(f) => (
                <List.Item
                  style={{ padding: '6px 4px', cursor: 'pointer' }}
                  onClick={() => handleOpen(f)}
                >
                  <Space size={6} style={{ width: '100%', flexWrap: 'wrap' }}>
                    {iconForExt(f.fileExtension)}
                    <Text style={{ fontSize: 12 }}>{f.fileName}</Text>
                    {actionTag(f.action)}
                    {typeof f.lineCount === 'number' && (
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        {f.lineCount} 行
                      </Text>
                    )}
                    <Text type="secondary" style={{ fontSize: 10, wordBreak: 'break-all', display: 'block', width: '100%' }}>
                      {f.filePath}
                    </Text>
                  </Space>
                </List.Item>
              )}
            />
          </div>
        ))
      )}

      <Modal
        open={open}
        onCancel={() => setOpen(false)}
        title={currentDetail ? `📄 ${currentDetail.fileName}` : '加载中...'}
        width={900}
        footer={
          <Space>
            <Button icon={<CopyOutlined />} onClick={handleCopy} disabled={!currentDetail}>
              复制
            </Button>
            <Button icon={<DownloadOutlined />} onClick={handleDownload} disabled={!currentDetail}>
              下载
            </Button>
            <Button type="primary" onClick={() => setOpen(false)}>
              关闭
            </Button>
          </Space>
        }
      >
        {detailLoading ? (
          <div style={{ padding: 60, textAlign: 'center' }}>
            <Spin />
          </div>
        ) : currentDetail ? (
          <>
            <div style={{ marginBottom: 8, fontSize: 12, color: '#888' }}>
              <Text code>{currentDetail.filePath}</Text>{' '}
              {actionTag(currentDetail.action)} ·{' '}
              <Text type="secondary">
                {currentDetail.lineCount} 行 · {currentDetail.sizeBytes} 字节
              </Text>
            </div>
            <pre
              style={{
                background: '#f6f8fa',
                border: '1px solid #eee',
                borderRadius: 4,
                padding: 12,
                fontSize: 12,
                lineHeight: 1.5,
                maxHeight: '60vh',
                overflow: 'auto',
                margin: 0,
                fontFamily: '"Cascadia Code","JetBrains Mono","SF Mono",Consolas,monospace',
              }}
            >
              {currentDetail.content}
            </pre>
          </>
        ) : (
          <Empty description="无内容" />
        )}
      </Modal>
    </div>
  );
};

export default GeneratedFilesView;
