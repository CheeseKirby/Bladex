-- H1 集成测试用 H2 兼容 schema(从 init.sql 提取,去 MySQL 特定语法: COMMENT/ON UPDATE/PROCEDURE/MEDIUMTEXT->CLOB)
CREATE TABLE IF NOT EXISTS ai_workflow_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id VARCHAR(100),
    project_name VARCHAR(200),
    master_plan_content CLOB,
    reception_id VARCHAR(100) UNIQUE,
    status VARCHAR(30) DEFAULT 'RECEIVED',
    source_service VARCHAR(100),
    write_target VARCHAR(10) DEFAULT 'ISOLATED',
    generation_identity_json CLOB,
    reference_profile_json CLOB,
    output_directory VARCHAR(500),
    compile_verification_status VARCHAR(50) DEFAULT 'NOT_RUN',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ai_workflow_sub_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    sub_plan_index INT,
    part_a_sub_plan_id VARCHAR(100),
    title VARCHAR(200),
    plan_content CLOB,
    prerequisites_json CLOB,
    status VARCHAR(30) DEFAULT 'QUEUED',
    error_message CLOB,
    git_commit_hash VARCHAR(40),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ai_workflow_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sub_plan_id BIGINT,
    stage VARCHAR(50),
    file_path VARCHAR(500),
    action VARCHAR(50),
    action_reason CLOB,
    llm_prompt CLOB,
    llm_response CLOB,
    validation_result CLOB,
    build_output CLOB,
    status VARCHAR(20),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ai_workflow_generated_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    sub_plan_id BIGINT NOT NULL,
    file_type VARCHAR(50),
    file_path VARCHAR(500) NOT NULL,
    file_name VARCHAR(200),
    file_extension VARCHAR(20),
    action VARCHAR(30),
    content CLOB,
    size_bytes INT,
    line_count INT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_deleted INT DEFAULT 0
);
