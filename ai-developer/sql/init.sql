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
-- 索引
-- -----------------------------------------------------------
CREATE INDEX idx_plan_status ON ai_workflow_plan(status);
CREATE INDEX idx_plan_project_id ON ai_workflow_plan(project_id);
CREATE INDEX idx_plan_reception_id ON ai_workflow_plan(reception_id);
CREATE INDEX idx_sub_plan_plan_id ON ai_workflow_sub_plan(plan_id);
CREATE INDEX idx_sub_plan_status ON ai_workflow_sub_plan(status);
CREATE INDEX idx_sub_plan_part_a_id ON ai_workflow_sub_plan(part_a_sub_plan_id);
CREATE INDEX idx_exec_log_sub_plan_id ON ai_workflow_execution_log(sub_plan_id);
CREATE INDEX idx_exec_log_status ON ai_workflow_execution_log(status);
CREATE INDEX idx_gen_file_plan_id ON ai_workflow_generated_file(plan_id);
CREATE INDEX idx_gen_file_sub_plan_id ON ai_workflow_generated_file(sub_plan_id);
