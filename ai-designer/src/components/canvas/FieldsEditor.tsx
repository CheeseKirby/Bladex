import React from 'react';
import { Table, Button, Input, Select, Checkbox, Tooltip } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import type { FieldConfig } from '../../types/plan';

interface FieldsEditorProps {
  value: FieldConfig[];
  onChange: (next: FieldConfig[]) => void;
}

const FIELD_TYPES = [
  { value: 'String', label: 'String (字符串)' },
  { value: 'Long', label: 'Long (长整数)' },
  { value: 'Integer', label: 'Integer (整数)' },
  { value: 'BigDecimal', label: 'BigDecimal (金额/小数)' },
  { value: 'Boolean', label: 'Boolean (是/否)' },
  { value: 'Date', label: 'Date (日期)' },
  { value: 'LocalDateTime', label: 'LocalDateTime (日期时间)' },
];

/**
 * 业务字段编辑器 — 给 ENTITY 模块配置弹窗使用。
 * 通过受控 value/onChange 与父组件 draft.fields 同步, 完全不可变更新(每次返回新数组)。
 *
 * 字段在传给 LLM 时会作为强约束 (BFF llm.ts 已经处理), LLM 生成 Entity/VO/DDL 时
 * 必须使用这些字段名/类型/注释,不再自由发挥。
 */
const FieldsEditor: React.FC<FieldsEditorProps> = ({ value, onChange }) => {
  const fields = value || [];

  const update = (idx: number, patch: Partial<FieldConfig>) => {
    const next = fields.map((f, i) => (i === idx ? { ...f, ...patch } : f));
    onChange(next);
  };

  const add = () => {
    onChange([
      ...fields,
      { name: '', type: 'String', comment: '', nullable: true },
    ]);
  };

  const remove = (idx: number) => {
    onChange(fields.filter((_, i) => i !== idx));
  };

  return (
    <div>
      <Table<FieldConfig & { __index: number }>
        size="small"
        pagination={false}
        rowKey={(_, i) => String(i)}
        dataSource={fields.map((f, i) => ({ ...f, __index: i }))}
        locale={{ emptyText: '尚无字段, 点下方按钮添加' }}
        columns={[
          {
            title: <Tooltip title="驼峰命名,如 productName">字段名</Tooltip>,
            dataIndex: 'name',
            width: 130,
            render: (_, record, idx) => (
              <Input
                size="small"
                placeholder="productName"
                value={record.name}
                onChange={(e) => update(idx, { name: e.target.value })}
              />
            ),
          },
          {
            title: '类型',
            dataIndex: 'type',
            width: 110,
            render: (_, record, idx) => (
              <Select
                size="small"
                style={{ width: '100%' }}
                value={record.type || 'String'}
                onChange={(v) => update(idx, { type: v })}
                options={FIELD_TYPES}
              />
            ),
          },
          {
            title: <Tooltip title="勾选=允许为空,不勾选=NotNull/NotBlank">允许空</Tooltip>,
            dataIndex: 'nullable',
            width: 50,
            align: 'center',
            render: (_, record, idx) => (
              <Checkbox
                checked={record.nullable !== false}
                onChange={(e) => update(idx, { nullable: e.target.checked })}
              />
            ),
          },
          {
            title: '注释',
            dataIndex: 'comment',
            render: (_, record, idx) => (
              <Input
                size="small"
                placeholder="商品名称"
                value={record.comment || ''}
                onChange={(e) => update(idx, { comment: e.target.value })}
              />
            ),
          },
          {
            title: '',
            width: 36,
            render: (_, _record, idx) => (
              <Button
                size="small"
                type="text"
                danger
                icon={<DeleteOutlined />}
                onClick={() => remove(idx)}
              />
            ),
          },
        ]}
      />
      <Button
        type="dashed"
        size="small"
        icon={<PlusOutlined />}
        onClick={add}
        style={{ marginTop: 8, width: '100%' }}
      >
        添加字段
      </Button>
    </div>
  );
};

export default FieldsEditor;
