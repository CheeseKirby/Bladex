-- ============================================================
-- Part B: AI代码生成引擎 — 数据库初始化脚本
-- 数据库: ai_workflow
-- ============================================================

CREATE DATABASE IF NOT EXISTS ai_workflow
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE ai_workflow;

-- -----------------------------------------------------------
-- 1. 接收的方案（对应Part A的一次传输）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_workflow_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    project_id VARCHAR(100) COMMENT 'Part A项目ID',
    project_name VARCHAR(200) COMMENT '项目名称',
    master_plan_content MEDIUMTEXT COMMENT '总方案内容(Markdown)',
    reception_id VARCHAR(100) UNIQUE COMMENT '接收编号',
    status VARCHAR(20) DEFAULT 'RECEIVED'
        COMMENT '状态: RECEIVED/EXECUTING/COMPLETED/FAILED',
    source_service VARCHAR(100) COMMENT '来源服务',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_deleted INT DEFAULT 0 COMMENT '逻辑删除'
) COMMENT 'AI工作流-接收的方案';

-- 阶段2: 写入目标列(幂等: 已存在则跳过, init.sql 可重复执行)
-- MySQL 8.0 的 ALTER TABLE ... ADD COLUMN 不支持 IF NOT EXISTS(MariaDB 才有),
-- 用 INFORMATION_SCHEMA 判断列是否存在,避免重复执行报错。
DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE add_column_if_missing(
    IN tbl VARCHAR(64),
    IN col VARCHAR(64),
    IN col_def VARCHAR(500)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_column_if_missing('ai_workflow_plan', 'write_target',
    "VARCHAR(10) NOT NULL DEFAULT 'ISOLATED' COMMENT '写入目标: ISOLATED(隔离区) / REAL(真实项目)'");

DROP PROCEDURE IF EXISTS add_column_if_missing;

-- -----------------------------------------------------------
-- 2. 子方案执行记录
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_workflow_sub_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    plan_id BIGINT NOT NULL COMMENT '关联方案ID',
    sub_plan_index INT COMMENT '序号',
    part_a_sub_plan_id VARCHAR(100) COMMENT 'Part A原始子方案ID（跨系统关联）',
    title VARCHAR(200) COMMENT '标题',
    plan_content MEDIUMTEXT COMMENT '子方案内容(Markdown)',
    prerequisites_json JSON COMMENT '前置依赖子方案ID列表',
    status VARCHAR(20) DEFAULT 'QUEUED'
        COMMENT '状态: QUEUED/EXECUTING/COMPLETED/FAILED',
    error_message TEXT COMMENT '失败原因',
    git_commit_hash VARCHAR(40) COMMENT 'Git提交哈希',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_deleted INT DEFAULT 0 COMMENT '逻辑删除'
) COMMENT 'AI工作流-子方案执行记录';

-- -----------------------------------------------------------
-- 3. 执行日志（细粒度追踪每次LLM调用和文件操作）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_workflow_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    sub_plan_id BIGINT COMMENT '关联子方案ID',
    stage VARCHAR(50)
        COMMENT '阶段: CHANGE_EVALUATION/CODE_GENERATION/VALIDATION/BUILD_VERIFY/SELF_REVIEW',
    file_path VARCHAR(500) COMMENT '目标文件路径',
    action VARCHAR(50) COMMENT '操作: CREATED/MODIFIED/SKIPPED/ROLLED_BACK',
    action_reason TEXT COMMENT '操作原因（特别是SKIP的原因）',
    llm_prompt MEDIUMTEXT COMMENT 'LLM提示词（如有）',
    llm_response MEDIUMTEXT COMMENT 'LLM响应（如有）',
    validation_result JSON COMMENT 'ConventionValidator校验结果',
    build_output TEXT COMMENT 'Maven编译输出',
    status VARCHAR(20) COMMENT '结果: SUCCESS/FAILED/SKIPPED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_deleted INT DEFAULT 0 COMMENT '逻辑删除'
) COMMENT 'AI工作流-执行日志';

-- -----------------------------------------------------------
-- 4. 生成的代码文件（供 Part A 拉取查看）
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_workflow_generated_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    plan_id BIGINT NOT NULL COMMENT '关联方案ID',
    sub_plan_id BIGINT NOT NULL COMMENT '关联子方案ID',
    file_type VARCHAR(50) COMMENT '文件类型: STANDARD_CRUD_ENTITY/CONTROLLER/...',
    file_path VARCHAR(500) NOT NULL COMMENT '目标文件路径(相对 target-project-root)',
    file_name VARCHAR(200) COMMENT '文件名(便于显示)',
    file_extension VARCHAR(20) COMMENT '扩展名: java/sql/xml',
    action VARCHAR(30) COMMENT '操作: CREATED/MODIFIED/SKIPPED/FAILED',
    content MEDIUMTEXT COMMENT '文件完整内容',
    size_bytes INT COMMENT '内容字节数',
    line_count INT COMMENT '行数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted INT DEFAULT 0 COMMENT '逻辑删除'
) COMMENT 'AI工作流-生成的代码文件';

-- -----------------------------------------------------------
-- 索引(幂等:已存在则跳过,init.sql 可重复执行不报错)
-- MySQL 没有 CREATE INDEX IF NOT EXISTS,用存储过程查 INFORMATION_SCHEMA 判断
-- -----------------------------------------------------------
DROP PROCEDURE IF EXISTS add_index_if_missing;
DELIMITER //
CREATE PROCEDURE add_index_if_missing(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_cols VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND index_name   = p_index
    ) THEN
        SET @sql = CONCAT('CREATE INDEX ', p_index, ' ON ', p_table, '(', p_cols, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//
DELIMITER ;

CALL add_index_if_missing('ai_workflow_plan',          'idx_plan_status',           'status');
CALL add_index_if_missing('ai_workflow_plan',          'idx_plan_project_id',       'project_id');
CALL add_index_if_missing('ai_workflow_plan',          'idx_plan_reception_id',     'reception_id');
CALL add_index_if_missing('ai_workflow_sub_plan',      'idx_sub_plan_plan_id',      'plan_id');
CALL add_index_if_missing('ai_workflow_sub_plan',      'idx_sub_plan_status',       'status');
CALL add_index_if_missing('ai_workflow_sub_plan',      'idx_sub_plan_part_a_id',    'part_a_sub_plan_id');
CALL add_index_if_missing('ai_workflow_execution_log','idx_exec_log_sub_plan_id',  'sub_plan_id');
CALL add_index_if_missing('ai_workflow_execution_log','idx_exec_log_status',         'status');
CALL add_index_if_missing('ai_workflow_generated_file','idx_gen_file_plan_id',      'plan_id');
CALL add_index_if_missing('ai_workflow_generated_file','idx_gen_file_sub_plan_id',  'sub_plan_id');

DROP PROCEDURE IF EXISTS add_index_if_missing;

