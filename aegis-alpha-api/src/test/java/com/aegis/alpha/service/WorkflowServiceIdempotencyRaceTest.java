package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P3-14: a lost race on the unique idempotency index returns the winning run. */
class WorkflowServiceIdempotencyRaceTest {
    private WorkflowMapper mapper;
    private LangChainGateway gateway;
    private WorkflowService service;
    private WorkflowRun winner;

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        gateway = mock(LangChainGateway.class);
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.issueServiceDelegation(anyString(), nullable(String.class), nullable(String.class), any(), anyLong()))
                .thenReturn("delegated-test-token");
        service = new WorkflowService(mapper, mock(AgentMapper.class), new ObjectMapper(), gateway,
                mock(CacheService.class), mock(BacktestService.class), mock(AgentTraceService.class),
                new WorkflowValidationService(), tokenService, false);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey("stock_analysis");
        definition.setName("Stock Analysis");
        definition.setEngine("langgraph");
        when(mapper.findDefinition("stock_analysis")).thenReturn(definition);
        winner = new WorkflowRun();
        winner.setRunId("winner");
        winner.setWorkflowKey("stock_analysis");
        winner.setSubject("AAPL");
        winner.setIdempotencyKey("k1");
        winner.setUserId("u1");
        winner.setTenantId("t1");
        winner.setStatus("RUNNING");
        // First lookup (before insert) misses: the concurrent request has not committed yet.
        when(mapper.findRunByIdempotencyKey("stock_analysis", "AAPL", "k1")).thenReturn(null, winner);
        doThrow(new DuplicateKeyException("idx_workflow_run_idempotency")).when(mapper).insertRun(any(WorkflowRun.class));
    }

    @Test
    void startReturnsExistingRunOnDuplicateKey() {
        WorkflowRun result = service.start("stock_analysis", "AAPL", new LinkedHashMap<String, Object>(), "k1");

        assertThat(result.getRunId()).isEqualTo("winner");
        verify(gateway, never()).executeWorkflow(any(), anyString(), any(), nullable(String.class), anyString(), eq(false), any());
        verify(mapper, never()).insertRunEvent(any());
    }

    @Test
    void queueStartReturnsExistingRunOnDuplicateKey() {
        WorkflowRun result = service.queueStart("stock_analysis", "AAPL", new LinkedHashMap<String, Object>(), "k1");

        assertThat(result.getRunId()).isEqualTo("winner");
    }

    @Test
    void duplicateKeyOwnedByAnotherUserIsRejected() {
        AuthenticatedPrincipal other = new AuthenticatedPrincipal("u2", "bob", "t1", Collections.singletonList("USER"));

        assertThatThrownBy(() -> service.start("stock_analysis", "AAPL", new LinkedHashMap<String, Object>(), "k1", other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency key already used");
    }

    @Test
    void duplicateWithoutIdempotencyKeyIsRethrown() {
        assertThatThrownBy(() -> service.start("stock_analysis", "AAPL", new LinkedHashMap<String, Object>()))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
