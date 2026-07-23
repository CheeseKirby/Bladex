package org.springblade.aiworkflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.agent.BladeXCodeAgent;
import org.springblade.aiworkflow.config.AiWorkflowProperties;
import org.springblade.aiworkflow.entity.AiPlan;
import org.springblade.aiworkflow.enums.PlanStatus;
import org.springblade.aiworkflow.mapper.AiExecutionLogMapper;
import org.springblade.aiworkflow.mapper.AiGeneratedFileMapper;
import org.springblade.aiworkflow.mapper.AiPlanMapper;
import org.springblade.aiworkflow.mapper.AiSubPlanMapper;
import org.springblade.aiworkflow.validation.PlanRequestValidator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanExecutionServiceClaimTest {

    private AiPlanMapper planMapper;
    private BladeXCodeAgent agent;
    private PlanExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        planMapper = mock(AiPlanMapper.class);
        agent = mock(BladeXCodeAgent.class);
        service = new PlanExecutionServiceImpl(
                planMapper, mock(AiSubPlanMapper.class), mock(AiGeneratedFileMapper.class),
                mock(AiExecutionLogMapper.class), agent, new ObjectMapper(), null,
                new AiWorkflowProperties(), new PlanRequestValidator("test-secret"));
    }

    @Test
    void triggerWithoutDatabaseClaimNeverExecutesTheWorkflow() {
        when(planMapper.update(any(), any())).thenReturn(0);

        service.executeAsync("rec-1");

        verify(agent, never()).executeWorkflow("rec-1");
    }

    @Test
    void claimFailureDoesNotLeaveAProcessLocalStaleLock() {
        when(planMapper.update(any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(1);
        AiPlan plan = new AiPlan();
        plan.setReceptionId("rec-2");
        plan.setStatus(PlanStatus.EXECUTING);
        plan.setWriteTarget("ISOLATED");
        when(planMapper.selectOne(any())).thenReturn(plan);

        service.executeAsync("rec-2");
        service.executeAsync("rec-2");

        verify(agent).executeWorkflow("rec-2");
    }
}
