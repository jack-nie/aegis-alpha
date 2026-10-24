package com.aegis.alpha.controller;

import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.AgentTraceService;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.BacktestService;
import com.aegis.alpha.service.CacheService;
import com.aegis.alpha.service.LangChainGateway;
import com.aegis.alpha.service.TokenService;
import com.aegis.alpha.service.WorkflowNodeExecutionService;
import com.aegis.alpha.service.WorkflowService;
import com.aegis.alpha.service.WorkflowValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** pi-style steering / follow-up queue endpoint: only the run owner may queue into a RUNNING run. */
class WorkflowRunMessageQueueEndpointTest {

    private WorkflowMapper mapper;
    private LangChainGateway gateway;
    private WorkflowService service;
    private WorkflowController controller;
    private final AuthenticatedPrincipal owner =
            new AuthenticatedPrincipal("u-a", "alice", "t-1", Collections.singletonList("portfolio_manager"));
    private final AuthenticatedPrincipal other =
            new AuthenticatedPrincipal("u-b", "bob", "t-1", Collections.singletonList("portfolio_manager"));
    private final AuthenticatedPrincipal admin =
            new AuthenticatedPrincipal("u-admin", "root", "t-1", Arrays.asList("admin"));
    private final AuthenticatedPrincipal otherTenant =
            new AuthenticatedPrincipal("u-a", "alice", "t-2", Collections.singletonList("portfolio_manager"));

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        gateway = mock(LangChainGateway.class);
        when(gateway.isEnabled()).thenReturn(true);
        service = new WorkflowService(
                mapper, mock(AgentMapper.class), new ObjectMapper(), gateway,
                mock(CacheService.class), mock(BacktestService.class), mock(AgentTraceService.class),
                new WorkflowValidationService(), mock(TokenService.class), false);
        AuthService authService = mock(AuthService.class);
        when(authService.principal("Bearer a")).thenReturn(owner);
        when(authService.principal("Bearer b")).thenReturn(other);
        when(authService.principal("Bearer admin")).thenReturn(admin);
        when(authService.principal("Bearer a2")).thenReturn(otherTenant);
        controller = new WorkflowController(authService, service, mock(WorkflowNodeExecutionService.class),
                mock(TokenService.class), Runnable::run);
        when(mapper.findRun("run-a")).thenAnswer(inv -> run("run-a", "RUNNING", "ACTIVE"));
        when(mapper.updateRunStatusIf(any(WorkflowRun.class), anyList(), anyBoolean())).thenReturn(1);
        when(mapper.findRun("run-done")).thenAnswer(inv -> run("run-done", "COMPLETED", "COMPLETED"));
        when(mapper.findRun("run-cancel")).thenAnswer(inv -> run("run-cancel", "RUNNING", "CANCEL_REQUESTED"));
    }

    @Test
    void ownerQueuesSteeringMessageIntoRunningRun() {
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("ok", true);
        accepted.put("position", 1);
        when(gateway.enqueueRunMessage("run-a", "steer", "stop, use MSFT", null, "u-a", "t-1")).thenReturn(accepted);

        ResponseEntity<Map<String, Object>> res = controller.queueRunMessage("Bearer a", "run-a",
                body("steering", "stop, use MSFT"));

        assertThat(res.getStatusCodeValue()).isEqualTo(202);
        assertThat(res.getBody()).containsEntry("ok", true).containsEntry("runId", "run-a").containsEntry("kind", "steer");
    }

    @Test
    void kindDefaultsToFollowUp() {
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("ok", true);
        when(gateway.enqueueRunMessage("run-a", "follow_up", "and then?", null, "u-a", "t-1")).thenReturn(accepted);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "and then?");
        assertThat(controller.queueRunMessage("Bearer a", "run-a", body).getStatusCodeValue()).isEqualTo(202);
    }

    @Test
    void agentIdTargetsOneParallelAgentAndTargetErrorsMapToHttpStatus() {
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("ok", true);
        when(gateway.enqueueRunMessage("run-a", "steer", "only risks", "val", "u-a", "t-1")).thenReturn(accepted);
        when(gateway.enqueueRunMessage("run-a", "steer", "x", "ghost", "u-a", "t-1"))
                .thenReturn(failure("INVALID_TARGET", 422));
        when(gateway.enqueueRunMessage("run-a", "steer", "x", "fund", "u-a", "t-1"))
                .thenReturn(failure("TARGET_FINISHED", 409));

        Map<String, Object> targeted = body("steer", "only risks");
        targeted.put("agentId", "val");
        ResponseEntity<Map<String, Object>> ok = controller.queueRunMessage("Bearer a", "run-a", targeted);
        assertThat(ok.getStatusCodeValue()).isEqualTo(202);
        assertThat(ok.getBody()).containsEntry("agentId", "val");

        Map<String, Object> ghost = body("steer", "x");
        ghost.put("agentId", "ghost");
        ResponseEntity<Map<String, Object>> invalid = controller.queueRunMessage("Bearer a", "run-a", ghost);
        assertThat(invalid.getStatusCodeValue()).isEqualTo(422);
        assertThat(invalid.getBody()).containsEntry("code", "INVALID_TARGET");

        Map<String, Object> fund = body("steer", "x");
        fund.put("agentId", "fund");
        assertThat(controller.queueRunMessage("Bearer a", "run-a", fund).getStatusCodeValue()).isEqualTo(409);

        Map<String, Object> notString = body("steer", "x");
        notString.put("agentId", 7);
        assertThat(controller.queueRunMessage("Bearer a", "run-a", notString).getStatusCodeValue()).isEqualTo(422);
        assertThat(controller.queueRunMessage("Bearer b", "run-a", notString).getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void foreignUserAdminAndOtherTenantGet404WithoutTouchingOrchestrator() {
        assertThat(controller.queueRunMessage("Bearer b", "run-a", body("steer", "x")).getStatusCodeValue()).isEqualTo(404);
        /* admins may read every run but must not steer someone else's agent loop */
        assertThat(controller.queueRunMessage("Bearer admin", "run-a", body("steer", "x")).getStatusCodeValue()).isEqualTo(404);
        assertThat(controller.queueRunMessage("Bearer a2", "run-a", body("steer", "x")).getStatusCodeValue()).isEqualTo(404);
        assertThat(controller.queueRunMessage("Bearer a", "missing", body("steer", "x")).getStatusCodeValue()).isEqualTo(404);
        verify(gateway, never()).enqueueRunMessage(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void unauthenticatedIs401() {
        assertThat(controller.queueRunMessage(null, "run-a", body("steer", "x")).getStatusCodeValue()).isEqualTo(401);
        verify(mapper, never()).findRun(anyString());
    }

    @Test
    void invalidBodyIs422ButForeignRunStill404() {
        assertThat(controller.queueRunMessage("Bearer a", "run-a", body("steer", "  ")).getStatusCodeValue()).isEqualTo(422);
        assertThat(controller.queueRunMessage("Bearer a", "run-a", null).getStatusCodeValue()).isEqualTo(422);
        assertThat(controller.queueRunMessage("Bearer a", "run-a", body("sideways", "x")).getStatusCodeValue()).isEqualTo(422);
        assertThat(controller.queueRunMessage("Bearer b", "run-a", body("steer", "")).getStatusCodeValue()).isEqualTo(404);
        verify(gateway, never()).enqueueRunMessage(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void finishedOrCancellingRunIs409() {
        ResponseEntity<Map<String, Object>> done = controller.queueRunMessage("Bearer a", "run-done", body("steer", "x"));
        assertThat(done.getStatusCodeValue()).isEqualTo(409);
        assertThat(done.getBody()).containsEntry("code", "RUN_NOT_RUNNING");
        assertThat(controller.queueRunMessage("Bearer a", "run-cancel", body("steer", "x")).getStatusCodeValue()).isEqualTo(409);
        verify(gateway, never()).enqueueRunMessage(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void orchestratorRejectionsMapToHttpStatus() {
        when(gateway.enqueueRunMessage(eq("run-a"), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(failure("QUEUE_FULL", 429))
                .thenReturn(failure("RUN_PAUSED", 409))
                .thenReturn(failure("ORCHESTRATOR_UNAVAILABLE", 502))
                .thenReturn(failure("SERVICE_AUTH", 401));

        ResponseEntity<Map<String, Object>> full = controller.queueRunMessage("Bearer a", "run-a", body("steer", "x"));
        assertThat(full.getStatusCodeValue()).isEqualTo(429);
        assertThat(full.getBody()).containsEntry("code", "QUEUE_FULL");
        assertThat(controller.queueRunMessage("Bearer a", "run-a", body("steer", "x")).getStatusCodeValue()).isEqualTo(409);
        assertThat(controller.queueRunMessage("Bearer a", "run-a", body("steer", "x")).getStatusCodeValue()).isEqualTo(502);
        assertThat(controller.queueRunMessage("Bearer a", "run-a", body("steer", "x")).getStatusCodeValue()).isEqualTo(502);
    }

    @Test
    void cancelClearsPendingQueueOfRunningRun() {
        assertThat(controller.cancelRun("Bearer a", "run-a").getStatusCodeValue()).isEqualTo(200);
        verify(gateway).clearRunMessageQueue("run-a", "u-a", "t-1");
    }

    @Test
    void cancelOfFinishedRunDoesNotTouchQueue() {
        controller.cancelRun("Bearer a", "run-done");
        verify(gateway, never()).clearRunMessageQueue(anyString(), any(), any());
    }

    private static Map<String, Object> body(String kind, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("message", message);
        return body;
    }

    private static Map<String, Object> failure(String code, int status) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("ok", false);
        failed.put("code", code);
        failed.put("httpStatus", status);
        return failed;
    }

    private static WorkflowRun run(String runId, String status, String controlStatus) {
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("daily");
        run.setUserId("u-a");
        run.setTenantId("t-1");
        run.setStatus(status);
        run.setControlStatus(controlStatus);
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        return run;
    }
}
