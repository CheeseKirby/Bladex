-- 修复 permit 菜单名 mojibake(UTF-8)
USE bladex;
UPDATE blade_menu SET name='作业票管理' WHERE code='permit' AND parent_id=0;
UPDATE blade_menu SET name='作业票' WHERE code='workPermit';
UPDATE blade_menu SET name='新增' WHERE code='workPermit_add';
UPDATE blade_menu SET name='修改' WHERE code='workPermit_edit';
UPDATE blade_menu SET name='删除' WHERE code='workPermit_delete';
UPDATE blade_menu SET name='查看' WHERE code='workPermit_view';
