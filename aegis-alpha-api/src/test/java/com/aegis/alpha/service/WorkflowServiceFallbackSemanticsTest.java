package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.domain.WorkflowVersion;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P3-15: Java fallback engine is explicit about condition nodes, missing handlers and cycles. */
class WorkflowServiceFallbackSemanticsTest {
    private WorkflowMapper mapper;
    private LangChainGateway gateway;
    private WorkflowService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<WorkflowRun> storedRun = new AtomicReference<>();
    private final List<WorkflowRunEvent> events = new ArrayList<>();
    private final List<WorkflowNodeRun> nodeRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        gateway = mock(LangChainGateway.class);
        when(gateway.isEnabled()).thenReturn(false);
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.issueServiceDelegation(anyString(), nullable(String.class), nullable(String.class), any(), anyLong()))
                .thenReturn("delegated-test-token");
        service = new WorkflowService(mapper, mock(AgentMapper.class), objectMapper, gateway,
                mock(CacheService.class), mock(BacktestService.class), mock(AgentTraceService.class),
                new WorkflowValidationService(), tokenService, false);
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).insertRun(any(WorkflowRun.class));
        doAnswer(inv -> { storedRun.set(copy(inv.getArgument(0))); return null; }).when(mapper).updateRun(any(WorkflowRun.class));
        CasMapperStubs.casOn(mapper, storedRun, this::copy);
        when(mapper.findRun(anyString())).thenAnswer(inv -> storedRun.get() == null ? null : copy(storedRun.get()));
        doAnswer(inv -> { events.add(inv.getArgument(0)); return null; }).when(mapper).insertRunEvent(any(WorkflowRunEvent.class));
        doAnswer(inv -> { nodeRuns.add(inv.getArgument(0)); return null; }).when(mapper).updateNodeRun(any(WorkflowNodeRun.class));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("summary", "done");
        when(gateway.executeNode(any(), anyMap(), anyMap(), anyString(), nullable(String.class))).thenReturn(ok);
    }

    @Test
    void conditionNodeIsRecordedAsNotEvaluated() {
        publish("{\"nodes\":[" + node("start", "start", "scheduler.manual") + ","
                + node("cond", "condition", null) + "," + node("agent", "agent", "general.agent") + ","
                + node("end", "end", "workflow.end") + "],\"edges\":["
                + edge("start", "cond") + "," + edge("cond", "agent") + "," + edge("agent", "end") + "]}");

        WorkflowRun run = service.start("custom", "AAPL", new LinkedHashMap<String, Object>());

        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo("CONDITION_NOT_EVALUATED");
            assertThat(e.getPayloadJson()).contains("cond");
        });
        WorkflowNodeRun condRun = nodeRuns.stream().filter(n -> "cond".equals(n.getNodeId())).findFirst().orElse(null);
        assertThat(condRun).isNotNull();
        assertThat(condRun.getOutputJson()).contains("\"evaluated\":false").doesNotContain("evaluated as pass");
    }

    @Test
    void nodeWithoutHandlerFailsValidationInsteadOfBeingSkipped() {
        publish("{\"nodes\":[" + node("start", "start", "scheduler.manual") + ","
                + "{\"id\":\"bare\",\"type\":\"workflowNode\",\"data\":{\"label\":\"Bare\"}}" + ","
                + node("end", "end", "workflow.end") + "],\"edges\":["
                + edge("start", "bare") + "," + edge("bare", "end") + "]}");

        WorkflowRun run = service.start("custom", "AAPL", new LinkedHashMap<String, Object>());

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorMessage()).contains("nodes without handler").contains("bare");
        verify(gateway, never()).executeNode(any(), anyMap(), anyMap(), anyString(), nullable(String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cyclicGraphFailsInsteadOfRunningInDeclarationOrder() throws Exception {
        // start() already rejects cycles via WorkflowValidationService; executionOrder is the
        // defence-in-depth for the fallback loop and no longer returns declaration order.
        Map<String, Object> layout = objectMapper.readValue("{\"nodes\":[" + node("a", "agent", "general.agent") + ","
                + node("b", "agent", "general.agent") + "],\"edges\":[" + edge("a", "b") + "," + edge("b", "a") + "]}", Map.class);

        assertThatThrownBy(() -> service.executionOrder((List<Map<String, Object>>) layout.get("nodes"),
                (List<Map<String, Object>>) layout.get("edges")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
        verify(gateway, never()).executeNode(any(), anyMap(), anyMap(), anyString(), nullable(String.class));
    }

    private void publish(String layout) {
        WorkflowVersion version = new WorkflowVersion();
        version.setVersionId("v1");
        version.setWorkflowKey("custom");
        version.setLayoutJson(layout);
        when(mapper.findLatestVersion("custom")).thenReturn(version);
    }

    private static String node(String id, String type, String handler) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"data\":{\"nodeType\":\"" + type + "\""
                + (handler == null ? "" : ",\"handler\":\"" + handler + "\"") + "}}";
    }

    private static String edge(String source, String target) {
        return "{\"id\":\"" + source + "-" + target + "\",\"source\":\"" + source + "\",\"target\":\"" + target + "\"}";
    }

    private WorkflowRun copy(WorkflowRun source) {
        return objectMapper.convertValue(source, WorkflowRun.class);
    }
}
