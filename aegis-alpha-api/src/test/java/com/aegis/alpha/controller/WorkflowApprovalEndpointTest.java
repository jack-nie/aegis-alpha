package com.aegis.alpha.controller;

import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.TokenService;
import com.aegis.alpha.service.WorkflowNodeExecutionService;
import com.aegis.alpha.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1-5: POST /_backend/workflow/runs/{runId}/approval status mapping. */
class WorkflowApprovalEndpointTest {

    private AuthService authService;
    private WorkflowService workflowService;
    private WorkflowController controller;
    private final AuthenticatedPrincipal desk =
            new AuthenticatedPrincipal("u-1", "desk", "t-1", Collections.singletonList("desk"));
    private final AuthenticatedPrincipal analyst =
            new AuthenticatedPrincipal("u-2", "analyst", "t-1", Collections.singletonList("research_user"));

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        workflowService = mock(WorkflowService.class);
        controller = new WorkflowController(authService, workflowService, mock(WorkflowNodeExecutionService.class),
                mock(TokenService.class), Runnable::run);
        when(authService.principal("Bearer desk")).thenReturn(desk);
        when(authService.principal("Bearer analyst")).thenReturn(analyst);
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");
        when(workflowService.runForPrincipal(eq("run-1"), any())).thenReturn(run);
        when(workflowService.isApprover(desk)).thenReturn(true);
        when(workflowService.isApprover(analyst)).thenReturn(false);
    }

    @Test
    void unauthenticatedIs401() {
        assertThat(controller.decideApproval(null, "run-1", body(true)).getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    void invisibleRunIs404() {
        assertThat(controller.decideApproval("Bearer desk", "other-run", body(true)).getStatusCodeValue()).isEqualTo(404);
    }

    @Test
    void nonApproverIs403AndNeverResumes() {
        ResponseEntity<Object> response = controller.decideApproval("Bearer analyst", "run-1", body(true));

        assertThat(response.getStatusCodeValue()).isEqualTo(403);
        verify(workflowService, never()).decideApproval(anyString(), anyBoolean(), nullable(String.class), any());
    }

    @Test
    void missingDecisionIs400() {
        Map<String, Object> noDecision = new LinkedHashMap<String, Object>();
        noDecision.put("comment", "looks fine");
        assertThat(controller.decideApproval("Bearer desk", "run-1", noDecision).getStatusCodeValue()).isEqualTo(400);
        Map<String, Object> stringDecision = new LinkedHashMap<String, Object>();
        stringDecision.put("approved", "true");
        assertThat(controller.decideApproval("Bearer desk", "run-1", stringDecision).getStatusCodeValue()).isEqualTo(400);
        verify(workflowService, never()).decideApproval(anyString(), anyBoolean(), nullable(String.class), any());
    }

    @Test
    void runNotAwaitingApprovalIs409() {
        when(workflowService.decideApproval(eq("run-1"), eq(true), nullable(String.class), eq(desk)))
                .thenThrow(new IllegalStateException("Workflow run is not awaiting human approval: run-1"));

        assertThat(controller.decideApproval("Bearer desk", "run-1", body(true)).getStatusCodeValue()).isEqualTo(409);
    }

    @Test
    void approverDecisionIsForwardedWithComment() {
        WorkflowRun decided = new WorkflowRun();
        decided.setStatus("REJECTED");
        when(workflowService.decideApproval("run-1", false, "weak evidence", desk)).thenReturn(decided);
        Map<String, Object> body = body(false);
        body.put("comment", "weak evidence");

        ResponseEntity<Object> response = controller.decideApproval("Bearer desk", "run-1", body);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(((WorkflowRun) response.getBody()).getStatus()).isEqualTo("REJECTED");
    }

    private static Map<String, Object> body(boolean approved) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("approved", approved);
        return body;
    }
}
