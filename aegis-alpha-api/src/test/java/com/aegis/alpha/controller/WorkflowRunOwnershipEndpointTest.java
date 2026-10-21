package com.aegis.alpha.controller;

import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1-18: run ownership through the HTTP layer (real WorkflowService, mocked persistence). */
class WorkflowRunOwnershipEndpointTest {

    private WorkflowMapper mapper;
    private WorkflowController controller;
    private final AuthenticatedPrincipal userA =
            new AuthenticatedPrincipal("u-a", "alice", "t-1", Collections.singletonList("portfolio_manager"));
    private final AuthenticatedPrincipal userB =
            new AuthenticatedPrincipal("u-b", "bob", "t-1", Collections.singletonList("portfolio_manager"));

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        WorkflowService service = new WorkflowService(
                mapper, mock(AgentMapper.class), new ObjectMapper(), mock(LangChainGateway.class),
                mock(CacheService.class), mock(BacktestService.class), mock(AgentTraceService.class),
                new WorkflowValidationService(), mock(TokenService.class), false);
        AuthService authService = mock(AuthService.class);
        when(authService.principal("Bearer a")).thenReturn(userA);
        when(authService.principal("Bearer b")).thenReturn(userB);
        controller = new WorkflowController(authService, service, mock(WorkflowNodeExecutionService.class),
                mock(TokenService.class), Runnable::run);

        when(mapper.findRun("run-a")).thenAnswer(inv -> run("run-a", "u-a", "t-1", "RUNNING"));
        when(mapper.findRuns()).thenReturn(Arrays.asList(
                run("run-a", "u-a", "t-1", "COMPLETED"),
                run("run-b", "u-b", "t-1", "COMPLETED"),
                run("legacy", null, null, "COMPLETED")));
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setRunId("run-a");
        when(mapper.findNodeRuns("run-a")).thenReturn(Collections.singletonList(nodeRun));
        when(mapper.findRunEvents("run-a")).thenReturn(Collections.<WorkflowRunEvent>emptyList());
    }

    @Test
    void otherUserGets404ForReadNodesEventsAndCancel() {
        assertThat(controller.run("Bearer b", "run-a").getStatusCodeValue()).isEqualTo(404);
        assertThat(controller.nodeRuns("Bearer b", "run-a").getStatusCodeValue()).isEqualTo(404);
        assertThat(controller.runEvents("Bearer b", "run-a").getStatusCodeValue()).isEqualTo(404);
        assertThat(controller.cancelRun("Bearer b", "run-a").getStatusCodeValue()).isEqualTo(404);
        assertThat(controller.pauseRun("Bearer b", "run-a").getStatusCodeValue()).isEqualTo(404);
        verify(mapper, never()).updateRun(any(WorkflowRun.class));
    }

    @Test
    void ownerCanReadAndCancel() {
        ResponseEntity<WorkflowRun> read = controller.run("Bearer a", "run-a");
        assertThat(read.getStatusCodeValue()).isEqualTo(200);
        assertThat(read.getBody().getRunId()).isEqualTo("run-a");
        assertThat(controller.nodeRuns("Bearer a", "run-a").getBody()).hasSize(1);
        assertThat(controller.cancelRun("Bearer a", "run-a").getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    void listContainsOnlyCallersRuns() {
        List<WorkflowRun> runs = controller.runs("Bearer a").getBody();
        assertThat(runs).extracting(WorkflowRun::getRunId).containsExactly("run-a");
        assertThat(controller.runs(null).getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    void unauthenticatedIs401() {
        assertThat(controller.run(null, "run-a").getStatusCodeValue()).isEqualTo(401);
        assertThat(controller.cancelRun(null, "run-a").getStatusCodeValue()).isEqualTo(401);
        verify(mapper, never()).findRun(anyString());
    }

    private static WorkflowRun run(String runId, String userId, String tenantId, String status) {
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("daily");
        run.setUserId(userId);
        run.setTenantId(tenantId);
        run.setStatus(status);
        run.setControlStatus("ACTIVE");
        run.setPauseRequested(0);
        run.setCancelRequested(0);
        return run;
    }
}
