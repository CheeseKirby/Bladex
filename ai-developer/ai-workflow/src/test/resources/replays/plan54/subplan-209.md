# 数据库 DDL - 建表语句

## 实体名: SpecialPeriod
## 模块名: specialperiod
## 表名: blade_special_period
## 包路径: org.springblade.specialperiod

## 任务描述
在 MySQL 数据库中创建 `blade_special_period` 特殊时段配置表，包含主键、业务字段、审计字段及必要索引。

## 建表 SQL
```sql
DROP TABLE IF EXISTS `blade_special_period`;
CREATE TABLE `blade_special_period` (
  `id`            BIGINT(20)  NOT NULL                COMMENT '主键ID',
  `period_name`   VARCHAR(64) NOT NULL                COMMENT '特殊时段名称',
  `period_type`   INT(2)      NOT NULL                COMMENT '时段类型(1节假日,2公休日,3夜间)',
  `start_date`    DATE        NOT NULL                COMMENT '特殊时段开始日期',
  `end_date`      DATE        NOT NULL                COMMENT '特殊时段结束日期',
  `start_time`    VARCHAR(8)  DEFAULT NULL            COMMENT '每日开始时间(HH:mm:ss)',
  `end_time`      VARCHAR(8)  DEFAULT NULL            COMMENT '每日结束时间(HH:mm:ss)',
  `upgrade_level` INT(2)      NOT NULL                COMMENT '升级审批级别',
  `is_upgrade`    INT(2)      NOT NULL DEFAULT 0      COMMENT '是否特殊时段升级作业(0否,1是)',
  `config_status` INT(2)      NOT NULL DEFAULT 0      COMMENT '配置状态(0待生效,1生效中,2已失效)',
  `remark`        VARCHAR(500) DEFAULT NULL           COMMENT '备注说明',
  `create_user`   BIGINT(20)  DEFAULT NULL            COMMENT '创建人',
  `create_time`   DATETIME    DEFAULT NULL            COMMENT '创建时间',
  `update_user`   BIGINT(20)  DEFAULT NULL            COMMENT '更新人',
  `update_time`   DATETIME    DEFAULT NULL            COMMENT '更新时间',
  `status`        INT(2)      DEFAULT NULL            COMMENT '状态',
  `is_deleted`    INT(2)      DEFAULT 0               COMMENT '是否已删除',
  PRIMARY KEY (`id`),
  KEY `idx_period_type` (`period_type`),
  KEY `idx_config_status` (`config_status`),
  KEY `idx_date_range` (`start_date`, `end_date`),
  KEY `idx_is_upgrade` (`is_upgrade`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='特殊时段配置表';
```

## 索引说明
- idx_period_type: 按时段类型查询
- idx_config_status: 按配置状态筛选
- idx_date_range: 日期区间冲突校验加速
- idx_is_upgrade: 升级作业标记查询

## 交付物
- 完整 DDL SQL 文件，可直接执行