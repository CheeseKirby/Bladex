import React, { useState } from 'react';
import { Button, Input, Typography } from 'antd';
import { EditOutlined, EyeOutlined } from '@ant-design/icons';
import PlanPreview from './PlanPreview';

const { TextArea } = Input;
const { Text } = Typography;

interface PlanEditorProps {
  content: string;
  onChange: (content: string) => void;
  readOnly?: boolean;
}

/** 可编辑Markdown方案（预览/编辑切换） */
const PlanEditor: React.FC<PlanEditorProps> = ({ content, onChange, readOnly }) => {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(content);

  const handleToggleEdit = () => {
    if (editing) {
      onChange(draft);
    } else {
      setDraft(content);
    }
    setEditing(!editing);
  };

  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 8,
          padding: '0 4px',
        }}
      >
        <Text type="secondary" style={{ fontSize: 12 }}>
          方案内容
        </Text>
        {!readOnly && (
          <Button
            size="small"
            icon={editing ? <EyeOutlined /> : <EditOutlined />}
            onClick={handleToggleEdit}
          >
            {editing ? '预览' : '编辑'}
          </Button>
        )}
      </div>

      {editing ? (
        <TextArea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          autoSize={{ minRows: 10, maxRows: 30 }}
          style={{ fontFamily: 'monospace', fontSize: 12 }}
        />
      ) : (
        <PlanPreview content={content} />
      )}
    </div>
  );
};

export default PlanEditor;
