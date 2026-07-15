package org.springblade.aiworkflow.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springblade.aiworkflow.controller.ConfigController.AdminTokenGuard;
import org.springblade.aiworkflow.service.IPlanExecutionService;
import org.springblade.aiworkflow.vo.PlanReceiveRequest;
import org.springblade.aiworkflow.vo.PlanReceiveResponse;

import java.util.LinkedHashMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanReceiverControllerTest {

    private IPlanExecutionService service;
    private PlanReceiverController controller;
    private HttpServletRequest servletRequest;

    @BeforeEach
    void setUp() {
        service = mock(IPlanExecutionService.class);
        controller = new PlanReceiverController(service, mock(AdminTokenGuard.class));
        servletRequest = mock(HttpServletRequest.class);
    }

    @Test
    void completedDuplicateMustNotBeExecutedAgain() {
        PlanReceiveRequest request = request();
        when(service.receivePlan(request)).thenReturn(response("rec-existing", "COMPLETED"));

        controller.receive(request, servletRequest);

        verify(service, never()).executeAsync("rec-existing");
    }

    @Test
    void newlyReceivedPlanMustBeQueued() {
        PlanReceiveRequest request = request();
        when(service.receivePlan(request)).thenReturn(response("rec-new", "RECEIVED"));

        controller.receive(request, servletRequest);

        verify(service).executeAsync("rec-new");
    }

    private PlanReceiveRequest request() {
        PlanReceiveRequest request = new PlanReceiveRequest();
        request.setProjectId("project-1");
        request.setProjectName("Project");
        return request;
    }

    private PlanReceiveResponse response(String id, String status) {
        PlanReceiveResponse response = new PlanReceiveResponse();
        response.setReceptionId(id);
        response.setStatus(status);
        response.setSubPlanStatuses(new LinkedHashMap<>());
        return response;
    }
}
