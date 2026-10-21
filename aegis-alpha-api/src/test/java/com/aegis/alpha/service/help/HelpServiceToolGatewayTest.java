package com.aegis.alpha.service.help;

import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.WorkflowService;
import com.aegis.alpha.service.help.tool.HelpToolCatalog;
import com.aegis.alpha.service.help.tool.HelpToolDefinition;
import com.aegis.alpha.service.help.tool.HelpToolGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2 integration: run diagnosis reads go through the unified read-only tool gateway,
 * and a gateway outage is reported as TOOL_UNAVAILABLE rather than as access denial.
 */
class HelpServiceToolGatewayTest {

    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "u-1", "alice", "tenant-1", Collections.<String>emptyList());

    @Test
    void diagnosisRoutesRunNodeAndEventReadsThroughTheGateway() {
        WorkflowService workflowService = mock(WorkflowService.class);
        String runId = "11111111-2222-3333-4444-555555555555";
        when(workflowService.runForPrincipal(eq(runId), eq(principal))).thenReturn(run(runId));
        when(workflowService.nodeRunsForPrincipal(eq(runId), eq(principal)))
                .thenReturn(Collections.singletonList(node("valuation", "FAILED", "null pointer")));
        when(workflowService.runEventsForPrincipal(eq(runId), eq(principal)))
                .thenReturn(Collections.singletonList(event("NODE_FAILED", "null pointer")));

        HelpToolGateway gateway = new HelpToolGateway(new HelpToolCatalog());
        HelpService helpService = new HelpService(
                mock(PlaybookService.class), workflowService, mock(WorkflowMapper.class),
                mock(KnowledgeService.class), mock(HelpLoopService.class), null, gateway);

        Map<String, Object> response = helpService.diagnoseRun("thread-1", "为什么失败？",
                intent(runId), principal);

        assertThat(response.get("provider")).isEqualTo("help-diagnosis");
        assertThat(response.get("runId")).isEqualTo(runId);
        assertThat(String.valueOf(response.get("content"))).contains("valuation");
        assertThat(String.valueOf(response.get("content"))).contains("null pointer");
        assertThat(gateway.metricsSnapshot().get("calls")).isEqualTo(3L);
        assertThat(gateway.auditSize()).isEqualTo(3);
        assertThat(gateway.audit(10)).allMatch(row -> Boolean.TRUE.equals(row.get("ok")));
    }

    @Test
    void timedOutRunReadIsReportedAsToolUnavailableNotAccessDenied() {
        WorkflowService workflowService = mock(WorkflowService.class);
        String runId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        when(workflowService.runForPrincipal(eq(runId), eq(principal))).thenAnswer(invocation -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return run(runId);
        });

        List<HelpToolDefinition> definitions = new ArrayList<HelpToolDefinition>();
        definitions.add(new HelpToolDefinition(HelpToolCatalog.GET_WORKFLOW_RUN,
                "read run", true, 30L, 0, HelpToolCatalog.CATEGORY_WORKFLOW));
        HelpToolGateway gateway = new HelpToolGateway(new HelpToolCatalog(definitions));
        HelpService helpService = new HelpService(
                mock(PlaybookService.class), workflowService, mock(WorkflowMapper.class),
                mock(KnowledgeService.class), mock(HelpLoopService.class), null, gateway);

        Map<String, Object> response = helpService.diagnoseRun("thread-1", "为什么失败？",
                intent(runId), principal);

        assertThat(response.get("ok")).isEqualTo(false);
        assertThat(response.get("escalateHuman")).isEqualTo(true);
        assertThat(response.get("runId")).isEqualTo(runId);
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("TOOL_UNAVAILABLE");
        assertThat(String.valueOf(response.get("toolError"))).containsIgnoringCase("timeout");
        assertThat(String.valueOf(response.get("content"))).doesNotContain("无权访问");
    }

    @Test
    void missingRunStaysAccessDenied() {
        WorkflowService workflowService = mock(WorkflowService.class);
        String runId = "cccccccc-dddd-eeee-ffff-000000000001";
        when(workflowService.runForPrincipal(eq(runId), eq(principal))).thenReturn(null);

        HelpToolGateway gateway = new HelpToolGateway(new HelpToolCatalog());
        HelpService helpService = new HelpService(
                mock(PlaybookService.class), workflowService, mock(WorkflowMapper.class),
                mock(KnowledgeService.class), mock(HelpLoopService.class), null, gateway);

        Map<String, Object> response = helpService.diagnoseRun("thread-1", "为什么失败？",
                intent(runId), principal);

        assertThat(response.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(response.get("content"))).isEqualTo("未找到运行或无权访问该运行。");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("ACCESS_DENIED");
    }

    private static HelpIntent.Result intent(String runId) {
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setDomain(HelpIntent.Domain.RUN_DIAGNOSIS);
        result.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);
        HelpIntent.Entities entities = new HelpIntent.Entities();
        entities.setRunId(runId);
        result.setEntities(entities);
        return result;
    }

    private static WorkflowRun run(String runId) {
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("deep_dive");
        run.setStatus("FAILED");
        run.setSubject("AAPL");
        run.setErrorMessage("node boom");
        run.setUserId("u-1");
        run.setTenantId("tenant-1");
        return run;
    }

    private static WorkflowNodeRun node(String name, String status, String error) {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setNodeId(name);
        node.setNodeName(name);
        node.setStatus(status);
        node.setErrorMessage(error);
        node.setAttempt(1);
        node.setMaxAttempts(1);
        return node;
    }

    private static WorkflowRunEvent event(String type, String message) {
        WorkflowRunEvent event = new WorkflowRunEvent();
        event.setEventType(type);
        event.setMessage(message);
        event.setCreatedAt("2026-07-21T12:00:00Z");
        return event;
    }
}
