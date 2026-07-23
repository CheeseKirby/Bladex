package org.springblade.aiworkflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springblade.aiworkflow.entity.AiExecutionLog;

import java.util.List;

/**
 * 执行日志Mapper
 *
 * @author AI Developer
 */
@Mapper
public interface AiExecutionLogMapper extends BaseMapper<AiExecutionLog> {

    /**
     * 根据子方案ID查询所有执行日志
     */
    @Select("SELECT * FROM ai_workflow_execution_log WHERE sub_plan_id = #{subPlanId} AND is_deleted = 0 ORDER BY create_time ASC, id ASC")
    List<AiExecutionLog> selectBySubPlanId(@Param("subPlanId") Long subPlanId);

    /**
     * 按方案ID(plan_id)查询所有子方案的执行日志(用于时间线展示)。
     * 排除 llm_prompt/llm_response 大字段。
     */
    @Select("SELECT l.id, l.plan_id, l.sub_plan_id, l.stage, l.file_path, l.action, l.action_reason, " +
            "l.validation_result, l.status, l.create_time, l.update_time, l.is_deleted " +
            "FROM ai_workflow_execution_log l " +
            "LEFT JOIN ai_workflow_sub_plan sp ON sp.id = l.sub_plan_id " +
            "WHERE (l.plan_id = #{planId} OR sp.plan_id = #{planId}) AND l.is_deleted = 0 " +
            "ORDER BY l.create_time ASC, l.id ASC")
    List<AiExecutionLog> selectByPlanId(@Param("planId") Long planId);
}
