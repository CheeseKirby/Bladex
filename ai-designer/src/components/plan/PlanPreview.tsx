import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { Typography, Empty } from 'antd';

const { Title } = Typography;

interface PlanPreviewProps {
  content: string;
  title?: string;
}

/**
 * Markdown 方案只读预览。
 *
 * 安全说明: 这里**不再**启用 rehype-raw。
 * 方案内容来自 LLM,直接渲染原始 HTML(<script>, <img onerror=...>)会形成 XSS。
 * 如果未来需要展示嵌入式 HTML,请改用 rehype-sanitize 配合白名单。
 */
const PlanPreview: React.FC<PlanPreviewProps> = ({ content, title }) => {
  if (!content) {
    return <Empty description="暂无方案内容" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  return (
    <div style={{ padding: 16 }}>
      {title && (
        <Title level={4} style={{ marginTop: 0 }}>
          {title}
        </Title>
      )}
      <div className="markdown-body" style={{ fontSize: 13, lineHeight: 1.8 }}>
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[rehypeHighlight]}
          // 显式禁止任何嵌入 HTML
          skipHtml
        >
          {content}
        </ReactMarkdown>
      </div>
    </div>
  );
};

export default PlanPreview;
