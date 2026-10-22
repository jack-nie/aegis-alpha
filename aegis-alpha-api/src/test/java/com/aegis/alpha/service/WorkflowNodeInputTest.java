package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P2-20: node input trimming, input_json cap, real per-node timeout and the run retry budget (java-fallback loop). */
class WorkflowNodeInputTest {
    private WorkflowMapper mapper;
    private LangChainGateway gateway;
    private WorkflowService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<WorkflowRun> storedRun = new AtomicReference<>();
    private final List<WorkflowNodeRun> nodeRuns = new CopyOnWriteArrayList<>();
    private final List<WorkflowRunEvent> events = new CopyOnWriteArrayList<>();
    private final Map<String, String> requestJsonByNode = new HashMap<>();

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
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey("chain");
        definition.setName("Chain");
        definition.setEngine("langgraph");
        when(mapper.findDefinition("chain")).thenReturn(definition);
        doAnswer(inv -> { storedRun.set(inv.getArgument(0)); return null; }).when(mapper).insertRun(any(WorkflowRun.class));
        doAnswer(inv -> { storedRun.set(inv.getArgument(0)); return null; }).when(mapper).updateRun(any(WorkflowRun.class));
        doAnswer(inv -> { nodeRuns.add(inv.getArgument(0)); return null; }).when(mapper).insertNodeRun(any(WorkflowNodeRun.class));
        doAnswer(inv -> { events.add(inv.getArgument(0)); return null; }).when(mapper).insertRunEvent(any(WorkflowRunEvent.class));
        when(mapper.findRun(anyString())).thenAnswer(inv -> {
            WorkflowRun current = storedRun.get();
            if (current == null) {
                return null;
            }
            WorkflowRun copy = new WorkflowRun();
            copy.setRunId(current.getRunId());
            copy.setWorkflowKey(current.getWorkflowKey());
            copy.setStatus(current.getStatus());
            copy.setSubject(current.getSubject());
            copy.setResultJson(current.getResultJson());
            copy.setControlStatus(current.getControlStatus());
            copy.setPauseRequested(current.getPauseRequested());
            copy.setCancelRequested(current.getCancelRequested());
            return copy;
        });
    }

    /** Wraps the agent nodes with start -> first ... last -> end so the layout validates. */
    private void useLayout(String nodesJson, String edgesJson, String first, String last) {
        String nodes = "{\"id\":\"start\",\"type\":\"start\",\"data\":{\"handler\":\"start\",\"nodeType\":\"start\"}},"
                + nodesJson + ",{\"id\":\"end\",\"type\":\"end\",\"data\":{\"handler\":\"end\",\"nodeType\":\"end\"}}";
        String edges = "{\"id\":\"es\",\"source\":\"start\",\"target\":\"" + first + "\"},"
                + "{\"id\":\"ee\",\"source\":\"" + last + "\",\"target\":\"end\"}"
                + (edgesJson.isEmpty() ? "" : "," + edgesJson);
        WorkflowVersion version = new WorkflowVersion();
        version.setVersionId("v-chain");
        version.setWorkflowKey("chain");
        version.setLayoutJson("{\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}");
        when(mapper.findLatestVersion("chain")).thenReturn(version);
        when(mapper.findVersion("v-chain")).thenReturn(version);
    }

    private static String agentNode(String id, String extraData) {
        return "{\"id\":\"" + id + "\",\"type\":\"agent\",\"data\":{\"handler\":\"finance.market_analysis\",\"nodeType\":\"agent\""
                + (extraData == null ? "" : "," + extraData) + "}}";
    }

    private void useChain(int length) {
        StringBuilder nodes = new StringBuilder();
        StringBuilder edges = new StringBuilder();
        for (int i = 1; i <= length; i++) {
            if (i > 1) {
                nodes.append(',');
                edges.append(i > 2 ? "," : "").append("{\"id\":\"e").append(i).append("\",\"source\":\"n").append(i - 1)
                        .append("\",\"target\":\"n").append(i).append("\"}");
            }
            nodes.append(agentNode("n" + i, null));
        }
        useLayout(nodes.toString(), edges.toString(), "n1", "n" + length);
    }

    private List<WorkflowNodeRun> n1Runs() {
        List<WorkflowNodeRun> runs = new ArrayList<>();
        for (WorkflowNodeRun nodeRun : nodeRuns) {
            if ("n1".equals(nodeRun.getNodeId())) {
                runs.add(nodeRun);
            }
        }
        return runs;
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void requestSizeDoesNotGrowWithChainLength() throws Exception {
        useChain(10);
        when(gateway.executeNode(any(), anyMap(), anyMap(), anyString(), nullable(String.class))).thenAnswer(inv -> {
            Map<String, Object> state = inv.getArgument(1);
            Map<String, Object> node = inv.getArgument(2);
            requestJsonByNode.put(String.valueOf(node.get("id")), objectMapper.writeValueAsString(state));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ok", true);
            output.put("summary", "summary of " + node.get("id"));
            output.put("analysis", repeat('x', 2000)); // large field not passed downstream
            return output;
        });

        WorkflowRun run = service.start("chain", "AAPL", new LinkedHashMap<String, Object>());

        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        int second = requestJsonByNode.get("n2").length();
        int tenth = requestJsonByNode.get("n10").length();
        assertThat(tenth).isLessThan(second + 50);
        assertThat(requestJsonByNode.get("n10")).contains("summary of n9").doesNotContain("summary of n8")
                .doesNotContain("xxxxxxxxxx");
        assertThat(run.getResultJson()).contains("summary of n1"); // the run result keeps the full state
    }

    @Test
    void declaredInputKeysAreTheOnlyUpstreamDataPassed() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("subject", "AAPL");
        Map<String, Object> market = new LinkedHashMap<>();
        market.put("summary", "m");
        market.put("raw", "big");
        state.put("market", market);
        state.put("news", Collections.singletonMap("summary", "n"));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "risk");
        node.put("data", Collections.singletonMap("inputKeys", Collections.singletonList("market")));
        Map<String, List<String>> upstream = new HashMap<>();
        upstream.put("risk", Arrays.asList("market", "news"));

        Map<String, Object> input = service.nodeInput(node, state, new HashSet<>(Collections.singletonList("subject")), upstream);

        assertThat(input).containsOnlyKeys("subject", "market");
        assertThat(input.get("market")).isEqualTo(market); // declared keys pass the full value
    }

    @Test
    void storedInputJsonIsCapped() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("subject", "AAPL");
        input.put("blob", repeat('y', WorkflowService.MAX_NODE_INPUT_JSON_CHARS + 10));

        String stored = service.cappedInputJson(input);

        assertThat(stored.length()).isLessThan(500);
        assertThat(stored).contains("\"truncated\":true").contains("\"blob\"");
    }

    @Test
    void slowNodeFailsNearItsTimeout() throws Exception {
        useLayout(agentNode("n1", "\"timeoutMs\":200"), "", "n1", "n1");
        when(gateway.executeNode(any(), anyMap(), anyMap(), anyString(), nullable(String.class))).thenAnswer(inv -> {
            Thread.sleep(5000);
            return Collections.singletonMap("ok", (Object) true);
        });

        long started = System.currentTimeMillis();
        WorkflowRun run = service.start("chain", "AAPL", new LinkedHashMap<String, Object>());
        long elapsed = System.currentTimeMillis() - started;

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(elapsed).isLessThan(2000);
        assertThat(n1Runs().get(0).getErrorMessage()).contains("timed out");
    }

    @Test
    void runRetryBudgetCapsRetriesAcrossNodes() throws Exception {
        service.setRunRetryBudget(1);
        String retry = "\"retryPolicy\":{\"maxAttempts\":3,\"backoffMs\":0}";
        useLayout(agentNode("n1", retry), "", "n1", "n1");
        when(gateway.executeNode(any(), anyMap(), anyMap(), anyString(), nullable(String.class)))
                .thenThrow(new OrchestratorCallException("down", new RuntimeException("down")));

        WorkflowRun run = service.start("chain", "AAPL", new LinkedHashMap<String, Object>());

        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(n1Runs()).hasSize(2); // 1 attempt + 1 budgeted retry, not 3 attempts
        List<String> types = new ArrayList<>();
        for (WorkflowRunEvent event : events) {
            types.add(event.getEventType());
        }
        assertThat(types).contains("RETRY_BUDGET_EXHAUSTED");
    }
}
