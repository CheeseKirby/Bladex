-- permit 作业级别业务字典(UTF-8)
USE bladex;
INSERT INTO blade_dict_biz (id, tenant_id, parent_id, code, dict_key, dict_value, sort, is_sealed, status, is_deleted) VALUES
(1800000000000000010, '000000', 0,                   'work_level', '-1', '作业级别', 0, 0, 1, 0),
(1800000000000000011, '000000', 1800000000000000010, 'work_level', '1',  '一级',     1, 0, 1, 0),
(1800000000000000012, '000000', 1800000000000000010, 'work_level', '2',  '二级',     2, 0, 1, 0),
(1800000000000000013, '000000', 1800000000000000010, 'work_level', '3',  '三级',     3, 0, 1, 0);
