CREATE TABLE `blade_hotwork` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `hotwork_id` BIGINT NOT NULL COMMENT '动火作业ID',
  `apply_code` VARCHAR(64) NOT NULL COMMENT '申请单编号',
  `apply_time` DATETIME NOT NULL COMMENT '申请时间',
  `hotwork_level` INT NOT NULL COMMENT '动火等级 字典 hotwork_level: 1-特殊动火 2-一级动火 3-二级动火',
  `work_content` VARCHAR(500) NOT NULL COMMENT '作业内容',
  `is_special_period` INT NOT NULL COMMENT '是否特殊时期 字段 is_special_period: 0-否 1-是',
  `upgrade_flag` INT NOT NULL COMMENT '升级标志 字段 upgrade_flag: 0-不升级 1-升级',
  `period_name` VARCHAR(128) NOT NULL COMMENT '时期名称',
  `period_type` INT NOT NULL COMMENT '时期类型 字段 period_type: 1-节假日 2-重大活动 3-敏感时段 4-其他',
  `start_time` DATETIME NOT NULL COMMENT '开始时间',
  `end_time` DATETIME NOT NULL COMMENT '结束时间',
  `create_user` BIGINT COMMENT '创建人',
  `create_time` DATETIME COMMENT '创建时间',
  `update_user` BIGINT COMMENT '更新人',
  `update_time` DATETIME COMMENT '更新时间',
  `status` INT DEFAULT 1 COMMENT '状态',
  `is_deleted` INT DEFAULT 0 COMMENT '是否已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hotwork_id` (`hotwork_id`),
  KEY `idx_apply_code` (`apply_code`),
  KEY `idx_apply_time` (`apply_time`),
  KEY `idx_hotwork_level` (`hotwork_level`),
  KEY `idx_start_end_time` (`start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动火作业表';

-- 字典: hotwork_level 动火等级
INSERT INTO blade_dict_biz (id,tenant_id,parent_id,code,dict_key,dict_value,sort,is_sealed,status,is_deleted) VALUES
(1800000000000000001,'000000',0,'hotwork_level','-1','动火等级',0,0,1,0),
(1800000000000000002,'000000',1800000000000000001,'hotwork_level','1','特殊动火',1,0,1,0),
(1800000000000000003,'000000',1800000000000000001,'hotwork_level','2','一级动火',2,0,1,0),
(1800000000000000004,'000000',1800000000000000001,'hotwork_level','3','二级动火',3,0,1,0);

-- 字典: is_special_period 是否特殊时期
INSERT INTO blade_dict_biz (id,tenant_id,parent_id,code,dict_key,dict_value,sort,is_sealed,status,is_deleted) VALUES
(1800000000000000010,'000000',0,'is_special_period','-1','是否特殊时期',0,0,1,0),
(1800000000000000011,'000000',1800000000000000010,'is_special_period','0','否',1,0,1,0),
(1800000000000000012,'000000',1800000000000000010,'is_special_period','1','是',2,0,1,0);

-- 字典: upgrade_flag 升级标志
INSERT INTO blade_dict_biz (id,tenant_id,parent_id,code,dict_key,dict_value,sort,is_sealed,status,is_deleted) VALUES
(1800000000000000020,'000000',0,'upgrade_flag','-1','升级标志',0,0,1,0),
(1800000000000000021,'000000',1800000000000000020,'upgrade_flag','0','不升级',1,0,1,0),
(1800000000000000022,'000000',1800000000000000020,'upgrade_flag','1','升级',2,0,1,0);

-- 字典: period_type 时期类型
INSERT INTO blade_dict_biz (id,tenant_id,parent_id,code,dict_key,dict_value,sort,is_sealed,status,is_deleted) VALUES
(1800000000000000030,'000000',0,'period_type','-1','时期类型',0,0,1,0),
(1800000000000000031,'000000',1800000000000000030,'period_type','1','节假日',1,0,1,0),
(1800000000000000032,'000000',1800000000000000030,'period_type','2','重大活动',2,0,1,0),
(1800000000000000033,'000000',1800000000000000030,'period_type','3','敏感时段',3,0,1,0),
(1800000000000000034,'000000',1800000000000000030,'period_type','4','其他',4,0,1,0);