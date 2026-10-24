package com.aegis.alpha.controller;

import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.TokenService;
import com.aegis.alpha.service.WorkflowNodeExecutionService;
import com.aegis.alpha.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P2-19: streaming runs are submitted to the injected, bounded workflow executor. */
class WorkflowStreamExecutorTest {

    @Test
    void streamingRunIsSubmittedToTheInjectedExecutor() {
        AuthService authService = mock(AuthService.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        AuthenticatedPrincipal user = new AuthenticatedPrincipal("u-1", "alice", "t-1", Collections.singletonList("research_user"));
        when(authService.principal("Bearer u")).thenReturn(user);
        final List<Runnable> submitted = new ArrayList<>();
        Executor capturing = submitted::add;
        WorkflowController controller = new WorkflowController(authService, workflowService,
                mock(WorkflowNodeExecutionService.class), mock(TokenService.class), capturing);

        SseEmitter emitter = controller.streamWorkflow("Bearer u", "daily", null);

        assertThat(emitter).isNotNull();
        assertThat(submitted).hasSize(1);
        verify(workflowService, never()).startWithStreaming(any(), any(), any(), any(), any());
        submitted.get(0).run();
        verify(workflowService).startWithStreaming(eq("daily"), nullable(String.class), any(), eq(emitter), eq(user));
    }
}
