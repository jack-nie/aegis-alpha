package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowVersion;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P3-13: run construction is shared and executeAsync runs the version recorded at create time. */
class WorkflowServiceRunVersionTest {
    private WorkflowMapper mapper;
    private LangChainGateway gateway;
    private BacktestService backtestService;
    private WorkflowService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<WorkflowRun> storedRun = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        gateway = mock(LangChainGateway.class);
        backtestService = mock(BacktestService.class);
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.issueServiceDelegation(anyString(), nullable(String.class), nullable(String.class), any(), anyLong()))
                .thenReturn("delegated-test-token");
        service = new WorkflowService(mapper, mock(AgentMapper.class), objectMapper, gateway,
                mock(CacheService.class), backtestService, mock(AgentTraceService.class),
                new WorkflowValidationService(), tokenService, false);
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).insertRun(any(WorkflowRun.class));
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).updateRun(any(WorkflowRun.class));
        CasMapperStubs.casOn(mapper, storedRun, this::copy);
        when(mapper.findRun(anyString())).thenAnswer(inv -> storedRun.get() == null ? null : copy(storedRun.get()));
        when(gateway.isEnabled()).thenReturn(true);
    }

    @Test
    void executeAsyncRunsTheVersionRecordedAtCreateEvenAfterANewPublish() {
        WorkflowVersion v1 = version("v1", "agent_v1");
        WorkflowVersion v2 = version("v2", "agent_v2");
        when(mapper.findLatestVersion("custom")).thenReturn(v1);
        when(mapper.findVersion("v1")).thenReturn(v1);
        when(mapper.findVersion("v2")).thenReturn(v2);
        when(gateway.executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean(), any()))
                .thenReturn(okResponse());

        WorkflowRun run = service.createRun("custom", "AAPL", new LinkedHashMap<String, Object>());
        assertThat(run.getWorkflowVersionId()).isEqualTo("v1");

        // A new version is published between create and execute.
        when(mapper.findLatestVersion("custom")).thenReturn(v2);
        service.executeAsync(run, new LinkedHashMap<String, Object>(), "custom");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> layout = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(gateway).executeWorkflow(layout.capture(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean(), any());
        assertThat(layout.getValue().get("workflowVersionId")).isEqualTo("v1");
        assertThat(layout.getValue().toString()).contains("agent_v1").doesNotContain("agent_v2");
        assertThat(storedRun.get().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void executeAsyncFailureMatchesStartAndRecordsBacktest() {
        WorkflowVersion v1 = version("v1", "agent_v1");
        when(mapper.findLatestVersion("custom")).thenReturn(v1);
        when(mapper.findVersion("v1")).thenReturn(v1);
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("ok", false);
        failed.put("error", "boom");
        when(gateway.executeWorkflow(anyMap(), anyString(), anyMap(), nullable(String.class), anyString(), anyBoolean(), any()))
                .thenReturn(failed);

        WorkflowRun run = service.createRun("custom", "AAPL", new LinkedHashMap<String, Object>());
        service.executeAsync(run, new LinkedHashMap<String, Object>(), "custom");

        assertThat(storedRun.get().getStatus()).isEqualTo("FAILED");
        assertThat(storedRun.get().getErrorMessage()).contains("boom");
        verify(backtestService).createFromWorkflowRun(argThat(r -> r != null && "FAILED".equals(r.getStatus())), any());
        verify(mapper).insertRunEvent(argThat(event -> "RUN_FAILED".equals(event.getEventType())));
    }

    @Test
    void allEntryPointsBuildRunsTheSameWay() {
        WorkflowVersion v1 = version("v1", "agent_v1");
        when(mapper.findLatestVersion("custom")).thenReturn(v1);
        when(mapper.findVersion("v1")).thenReturn(v1);
        List<WorkflowRun> inserted = new ArrayList<>();
        doAnswer(inv -> { WorkflowRun r = copy(inv.getArgument(0)); inserted.add(r); storedRun.set(r); return null; })
                .when(mapper).insertRun(any(WorkflowRun.class));

        service.createRun("custom", "AAPL", new LinkedHashMap<String, Object>());
        service.queueStart("custom", "AAPL", new LinkedHashMap<String, Object>(), "idem-1");

        assertThat(inserted).hasSize(2);
        WorkflowRun created = inserted.get(0);
        WorkflowRun queued = inserted.get(1);
        assertThat(created.getStatus()).isEqualTo("RUNNING");
        assertThat(created.getControlStatus()).isEqualTo("ACTIVE");
        assertThat(created.getQueuedAt()).isNull();
        assertThat(queued.getStatus()).isEqualTo("QUEUED");
        assertThat(queued.getControlStatus()).isEqualTo("QUEUED");
        assertThat(queued.getQueuedAt()).isNotNull();
        assertThat(queued.getIdempotencyKey()).isEqualTo("idem-1");
        for (WorkflowRun r : inserted) {
            assertThat(r.getWorkflowVersionId()).isEqualTo("v1");
            assertThat(r.getRunId()).isNotBlank();
            assertThat(r.getTraceId()).isNotBlank();
            assertThat(r.getPauseRequested()).isEqualTo(0);
            assertThat(r.getCancelRequested()).isEqualTo(0);
            assertThat(r.getNodeCount()).isEqualTo(0);
        }
    }

    private static WorkflowVersion version(String id, String agentId) {
        String layout = "{\"nodes\":["
                + "{\"id\":\"start\",\"type\":\"start\",\"data\":{\"handler\":\"start\",\"nodeType\":\"start\"}},"
                + "{\"id\":\"n1\",\"type\":\"agent\",\"data\":{\"handler\":\"finance.market_analysis\",\"nodeType\":\"agent\",\"agentId\":\"" + agentId + "\"}},"
                + "{\"id\":\"end\",\"type\":\"end\",\"data\":{\"handler\":\"end\",\"nodeType\":\"end\"}}],"
                + "\"edges\":[{\"id\":\"e1\",\"source\":\"start\",\"target\":\"n1\"},"
                + "{\"id\":\"e2\",\"source\":\"n1\",\"target\":\"end\"}]}";
        WorkflowVersion version = new WorkflowVersion();
        version.setVersionId(id);
        version.setWorkflowKey("custom");
        version.setLayoutJson(layout);
        return version;
    }

    private static Map<String, Object> okResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("final_state", new LinkedHashMap<String, Object>());
        response.put("trace", new ArrayList<Object>());
        response.put("node_outputs", new LinkedHashMap<String, Object>());
        return response;
    }

    private WorkflowRun copy(WorkflowRun source) {
        return objectMapper.convertValue(source, WorkflowRun.class);
    }
}
