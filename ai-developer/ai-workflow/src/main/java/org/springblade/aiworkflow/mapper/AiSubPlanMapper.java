package org.springblade.aiworkflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springblade.aiworkflow.entity.AiSubPlan;

import java.util.List;

/**
 * 子方案Mapper
 *
 * @author AI Developer
 */
@Mapper
public interface AiSubPlanMapper extends BaseMapper<AiSubPlan> {

    /**
     * 根据方案ID查询所有子方案
     */
    @Select("SELECT * FROM ai_workflow_sub_plan WHERE plan_id = #{planId} AND is_deleted = 0 ORDER BY sub_plan_index ASC")
    List<AiSubPlan> selectByPlanId(@Param("planId") Long planId);

    /**
     * 查询所有QUEUED状态的子方案。
     * 注意：此方法不检查前置依赖是否完成，调用方需在Java层自行校验DAG依赖。
     */
    @Select("SELECT * FROM ai_workflow_sub_plan WHERE plan_id = #{planId} AND status = 'QUEUED' AND is_deleted = 0 ORDER BY sub_plan_index ASC")
    List<AiSubPlan> selectQueuedByPlanId(@Param("planId") Long planId);
}
