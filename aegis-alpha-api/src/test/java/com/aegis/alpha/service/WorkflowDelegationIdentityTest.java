package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P2-21: the portfolio:read delegation carries only the authenticated identity. */
class WorkflowDelegationIdentityTest {
    private WorkflowMapper mapper;
    private LangChainGateway gateway;
    private TokenService tokenService;
    private WorkflowService service;
    private final AtomicReference<WorkflowRun> storedRun = new AtomicReference<>();
    private final List<WorkflowRunEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        gateway = mock(LangChainGateway.class);
        tokenService = mock(TokenService.class);
        when(tokenService.issueServiceDelegation(anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn("delegated-test-token");
        service = new WorkflowService(mapper, mock(AgentMapper.class), new ObjectMapper(), gateway,
                mock(CacheService.class), mock(BacktestService.class), mock(AgentTraceService.class),
                new WorkflowValidationService(), tokenService, false);
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey("daily");
        definition.setName("Daily");
        definition.setEngine("langgraph");
        when(mapper.findDefinition("daily")).thenReturn(definition);
        doAnswer(inv -> { storedRun.set(inv.getArgument(0)); return null; }).when(mapper).insertRun(any(WorkflowRun.class));
        doAnswer(inv -> { storedRun.set(inv.getArgument(0)); return null; }).when(mapper).updateRun(any(WorkflowRun.class));
        doAnswer(inv -> { events.add(inv.getArgument(0)); return null; }).when(mapper).insertRunEvent(any(WorkflowRunEvent.class));
        when(mapper.findRun(anyString())).thenAnswer(inv -> storedRun.get());
        when(gateway.isEnabled()).thenReturn(true);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("final_state", new LinkedHashMap<String, Object>());
        response.put("trace", new ArrayList<Object>());
        response.put("node_outputs", new LinkedHashMap<String, Object>());
        when(gateway.executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean(), any()))
                .thenReturn(response);
    }

    private static Map<String, Object> forgedInputs() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("userId", "victim-user");
        inputs.put("tenantId", "victim-tenant");
        return inputs;
    }

    @Test
    void forgedInputIdentityNeverReachesTheToken() {
        AuthenticatedPrincipal alice = new AuthenticatedPrincipal("u-alice", "alice", "t-1",
                Collections.singletonList("research_user"));

        service.start("daily", "AAPL", forgedInputs(), null, alice);

        verify(tokenService).issueServiceDelegation(anyString(), eq("u-alice"), eq("t-1"), anyList(), anyLong());
        verify(tokenService, never()).issueServiceDelegation(anyString(), eq("victim-user"), anyString(), anyList(), anyLong());
        verify(gateway).executeWorkflow(anyMap(), anyString(), anyMap(), eq("delegated-test-token"), anyString(), anyBoolean(), any());
    }

    @Test
    void noIdentityMeansNoTokenAndARecordedEvent() {
        service.start("daily", "AAPL", forgedInputs());

        verify(tokenService, never()).issueServiceDelegation(anyString(), anyString(), anyString(), anyList(), anyLong());
        verify(gateway).executeWorkflow(anyMap(), anyString(), anyMap(), isNull(), anyString(), anyBoolean(), any());
        List<String> types = new ArrayList<>();
        for (WorkflowRunEvent event : events) {
            types.add(event.getEventType());
        }
        assertThat(types).contains("DELEGATION_SKIPPED");
    }

    @Test
    void directIssueIgnoresInputIdentity() {
        assertThat(service.issueDelegatedPortfolioReadToken("run-1", null, null, forgedInputs())).isNull();
        assertThat(service.issueDelegatedPortfolioReadToken("run-1", "u-1", null, forgedInputs()))
                .isEqualTo("delegated-test-token");
        verify(tokenService).issueServiceDelegation(eq("run-1"), eq("u-1"), eq("default"), anyList(), anyLong());
    }
}
