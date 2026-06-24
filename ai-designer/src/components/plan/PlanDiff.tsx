import React from 'react';
import { Typography, Empty } from 'antd';

const { Text } = Typography;

interface PlanDiffProps {
  original: string;
  revised: string;
  changeLog?: { what: string; why: string; before: string; after: string }[];
}

/** 方案版本差异对比 */
const PlanDiff: React.FC<PlanDiffProps> = ({ original, revised, changeLog }) => {
  if (!original && !revised) {
    return <Empty description="无差异数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  // 简化版 diff：显示修改日志
  return (
    <div style={{ padding: 8, fontSize: 12 }}>
      {changeLog && changeLog.length > 0 ? (
        <div>
          <Text strong style={{ fontSize: 13 }}>
            修改日志 ({changeLog.length} 项)
          </Text>
          {changeLog.map((entry, i) => (
            <div
              key={i}
              style={{
                marginTop: 8,
                padding: 8,
                background: '#f6ffed',
                borderRadius: 4,
                border: '1px solid #b7eb8f',
              }}
            >
              <div>
                <Text strong>修改: </Text>
                <Text>{entry.what}</Text>
              </div>
              <div>
                <Text type="secondary">原因: </Text>
                <Text>{entry.why}</Text>
              </div>
              <div style={{ marginTop: 4 }}>
                <Text delete type="danger" style={{ fontSize: 11 }}>
                  {entry.before}
                </Text>
                <br />
                <Text type="success" style={{ fontSize: 11 }}>
                  {entry.after}
                </Text>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div style={{ display: 'flex', gap: 8 }}>
          <div style={{ flex: 1 }}>
            <Text strong>原始版本</Text>
            <pre
              style={{
                fontSize: 10,
                background: '#fff1f0',
                padding: 8,
                borderRadius: 4,
                maxHeight: 300,
                overflow: 'auto',
              }}
            >
              {original.slice(0, 1000)}
            </pre>
          </div>
          <div style={{ flex: 1 }}>
            <Text strong>修订版本</Text>
            <pre
              style={{
                fontSize: 10,
                background: '#f6ffed',
                padding: 8,
                borderRadius: 4,
                maxHeight: 300,
                overflow: 'auto',
              }}
            >
              {revised.slice(0, 1000)}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
};

export default PlanDiff;
