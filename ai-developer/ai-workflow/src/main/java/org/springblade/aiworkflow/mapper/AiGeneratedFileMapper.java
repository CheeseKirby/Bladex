package org.springblade.aiworkflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springblade.aiworkflow.entity.AiGeneratedFile;

import java.util.List;

/**
 * 生成文件 Mapper
 *
 * @author AI Developer
 */
@Mapper
public interface AiGeneratedFileMapper extends BaseMapper<AiGeneratedFile> {

    /** 按 plan 列出所有文件(不含 content,避免大字段) */
    @Select("SELECT id, plan_id, sub_plan_id, file_type, file_path, file_name, file_extension, " +
            "action, size_bytes, line_count, create_time, is_deleted " +
            "FROM ai_workflow_generated_file " +
            "WHERE plan_id = #{planId} AND is_deleted = 0 " +
            "ORDER BY sub_plan_id ASC, id ASC")
    List<AiGeneratedFile> selectByPlanIdWithoutContent(@Param("planId") Long planId);

    /** 按 sub plan 列出文件(不含 content) */
    @Select("SELECT id, plan_id, sub_plan_id, file_type, file_path, file_name, file_extension, " +
            "action, size_bytes, line_count, create_time, is_deleted " +
            "FROM ai_workflow_generated_file " +
            "WHERE sub_plan_id = #{subPlanId} AND is_deleted = 0 " +
            "ORDER BY id ASC")
    List<AiGeneratedFile> selectBySubPlanIdWithoutContent(@Param("subPlanId") Long subPlanId);

    /** 按 plan 列出所有文件 (含 content,供跨文件校验使用) */
    @Select("SELECT * FROM ai_workflow_generated_file " +
            "WHERE plan_id = #{planId} AND is_deleted = 0 " +
            "ORDER BY sub_plan_id ASC, id ASC")
    List<AiGeneratedFile> selectByPlanId(@Param("planId") Long planId);
}
