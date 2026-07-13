package org.springblade.aiworkflow.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.entity.AiSubPlan;
import org.springblade.aiworkflow.enums.PlanStatus;
import org.springblade.aiworkflow.enums.SubPlanStatus;
import org.springblade.aiworkflow.mapper.AiPlanMapper;
import org.springblade.aiworkflow.mapper.AiSubPlanMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动恢复 - C2: 进程崩溃/kill 后 EXECUTING 状态的 plan/subPlan 永久卡死,无自动恢复。
 *
 * <p>启动时扫描所有 EXECUTING 状态的 plan/subPlan,重置为 FAILED 并记"进程重启恢复"。
 * inFlight 是内存态(ConcurrentHashMap.newKeySet),重启即失,无法防重启后重复触发;
 * 本 Runner 确保崩溃后 DB 状态收敛,UI 不会永远显示"执行中"。
 *
 * <p>注意:仅重置状态,不重新入队执行(崩溃时磁盘已写部分文件,DB 与磁盘可能不一致,
 * 重新执行会重复写盘;由人工决定是否重新提交 plan)。
 *
 * @author AI Developer
 */
@Slf4j
@Component
public class StartupRecoveryRunner implements ApplicationRunner {

    private final AiPlanMapper planMapper;
    private final AiSubPlanMapper subPlanMapper;

    public StartupRecoveryRunner(AiPlanMapper planMapper, AiSubPlanMapper subPlanMapper) {
        this.planMapper = planMapper;
        this.subPlanMapper = subPlanMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 扫描 EXECUTING plan(进程重启前中断的),重置为 FAILED
        List<AiPlan> executingPlans = planMapper.selectList(
                new LambdaQueryWrapper<AiPlan>().eq(AiPlan::getStatus, PlanStatus.EXECUTING));
        int planCount = 0;
        for (AiPlan plan : executingPlans) {
            plan.setStatus(PlanStatus.FAILED);
            planMapper.updateById(plan);
            planCount++;
            log.warn("启动恢复: plan {} 从 EXECUTING 重置为 FAILED(进程重启前中断)", plan.getReceptionId());
        }

        // 扫描 EXECUTING subPlan,重置为 FAILED
        List<AiSubPlan> executingSubPlans = subPlanMapper.selectList(
                new LambdaQueryWrapper<AiSubPlan>().eq(AiSubPlan::getStatus, SubPlanStatus.EXECUTING));
        int subPlanCount = 0;
        for (AiSubPlan sp : executingSubPlans) {
            sp.setStatus(SubPlanStatus.FAILED);
            sp.setErrorMessage("进程重启,执行中断(启动恢复)");
            sp.setCompletedAt(LocalDateTime.now());
            subPlanMapper.updateById(sp);
            subPlanCount++;
        }

        if (planCount > 0 || subPlanCount > 0) {
            log.warn("启动恢复完成: {} 个 plan + {} 个 subPlan 从 EXECUTING 重置为 FAILED", planCount, subPlanCount);
        }
    }
}
