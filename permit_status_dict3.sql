-- 重建 work_permit_status 字典(加"审批未通过"50)
USE bladex;
DELETE FROM blade_dict_biz WHERE code='work_permit_status';
INSERT INTO blade_dict_biz (id, tenant_id, parent_id, code, dict_key, dict_value, sort, is_sealed, status, is_deleted) VALUES
(1800000000000000020, '000000', 0,                    'work_permit_status', '-1', '作业票状态',   0, 0, 1, 0),
(1800000000000000021, '000000', 1800000000000000020,  'work_permit_status', '1',  '草稿',         1, 0, 1, 0),
(1800000000000000022, '000000', 1800000000000000020,  'work_permit_status', '20', '待审批',       2, 0, 1, 0),
(1800000000000000024, '000000', 1800000000000000020,  'work_permit_status', '40', '已审批',       3, 0, 1, 0),
(1800000000000000033, '000000', 1800000000000000020,  'work_permit_status', '50', '审批未通过',   4, 0, 1, 0),
(1800000000000000027, '000000', 1800000000000000020,  'work_permit_status', '70', '已完成',       5, 0, 1, 0),
(1800000000000000030, '000000', 1800000000000000020,  'work_permit_status', '91', '已取消',       6, 0, 1, 0);
