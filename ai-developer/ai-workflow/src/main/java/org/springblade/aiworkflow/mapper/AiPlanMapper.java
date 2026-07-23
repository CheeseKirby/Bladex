package org.springblade.aiworkflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springblade.aiworkflow.entity.AiPlan;

/**
 * 方案Mapper
 *
 * @author AI Developer
 */
@Mapper
public interface AiPlanMapper extends BaseMapper<AiPlan> {

    /** Locking read used after a concurrent unique-key conflict under REPEATABLE_READ. */
    @Select("SELECT * FROM ai_workflow_plan WHERE idempotency_key = #{key} AND is_deleted = 0 LIMIT 1 FOR UPDATE")
    AiPlan selectByIdempotencyKeyForUpdate(@Param("key") String key);
}
