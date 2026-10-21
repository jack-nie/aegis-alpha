package com.aegis.alpha.service.help;

import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HelpServiceTest {

    private PlaybookService playbookService;
    private WorkflowService workflowService;
    private WorkflowMapper workflowMapper;
    private KnowledgeService knowledgeService;
    private HelpLoopService helpLoopService;
    private HelpService helpService;

    @BeforeEach
    void setUp() {
        playbookService = new PlaybookService();
        playbookService.reload();
        workflowService = mock(WorkflowService.class);
        workflowMapper = mock(WorkflowMapper.class);
        knowledgeService = new KnowledgeService();
        knowledgeService.reload();
        helpLoopService = new HelpLoopService(knowledgeService);
        helpService = new HelpService(playbookService, workflowService, workflowMapper, knowledgeService, helpLoopService);
    }

    @Test
    void answerFromPlaybookReturnsEnvelope() {
        HelpIntent.Result intent = HelpIntent.Result.empty();
        intent.setDomain(HelpIntent.Domain.PRODUCT_HELP);
        intent.setRouteAction(HelpIntent.RouteAction.PLAYBOOK);
        intent.setPlaybookId("degraded-meaning");

        Map<String, Object> response = helpService.answerFromPlaybook("t1", "什么是 DEGRADED？", intent);

        assertThat(response).isNotNull();
        assertThat(response.get("threadId")).isEqualTo("t1");
        assertThat(String.valueOf(response.get("content"))).contains("DEGRADED");
        assertThat(response.get("domain")).isEqualTo("PRODUCT_HELP");
        assertThat(response.get("routeAction")).isEqualTo("PLAYBOOK");
        assertThat(response.get("provider")).isEqualTo("help-playbook");
        assertThat(response.get("sourceRefs")).isNotNull();
    }

    @Test
    void diagnoseMissingRunEscalates() {
        when(workflowService.run("missing-id")).thenReturn(null);
        HelpIntent.Result intent = diagnosisIntent("missing-id");

        Map<String, Object> response = helpService.diagnoseRun("t1", "为什么失败", intent);

        assertThat(response.get("ok")).isEqualTo(false);
        assertThat(response.get("escalateHuman")).isEqualTo(true);
        assertThat(String.valueOf(response.get("content"))).contains("未找到运行");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("RUN_NOT_FOUND");
    }

    @Test
    void diagnoseDegradedRunPrioritizesProblemNodesAndEvents() {
        String runId = "11111111-2222-3333-4444-555555555555";
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("deep_dive");
        run.setStatus("DEGRADED");
        run.setSubject("AAPL");
        run.setErrorMessage("evidence incomplete");
        run.setStartedAt("2026-07-21T10:00:00Z");
        run.setCompletedAt("2026-07-21T10:05:00Z");
        run.setNodeCount(3);
        when(workflowService.run(runId)).thenReturn(run);

        WorkflowNodeRun ok = node("n0", "hydrate", "COMPLETED", null, null);
        WorkflowNodeRun bad = node("n1", "critique", "DEGRADED", "missing claims",
                "{\"summary\":\"evidence gate failed\",\"ok\":false}");
        WorkflowNodeRun ok2 = node("n2", "aggregate", "COMPLETED", null, null);
        when(workflowMapper.findNodeRuns(runId)).thenReturn(Arrays.asList(ok, bad, ok2));

        WorkflowRunEvent e1 = event("NODE_COMPLETED", "n0", "hydrate ok", "2026-07-21T10:01:00Z");
        WorkflowRunEvent e2 = event("NODE_FAILED", "n1", "critique degraded", "2026-07-21T10:03:00Z");
        WorkflowRunEvent e3 = event("RUN_COMPLETED", null, "finished degraded", "2026-07-21T10:05:00Z");
        when(workflowMapper.findRunEvents(runId)).thenReturn(Arrays.asList(e1, e2, e3));

        Map<String, Object> response = helpService.diagnoseRun("t1", "为什么 degraded", diagnosisIntent(runId));
        String content = String.valueOf(response.get("content"));

        assertThat(response.get("runStatus")).isEqualTo("DEGRADED");
        assertThat(response.get("provider")).isEqualTo("help-diagnosis");
        assertThat(response.get("escalateHuman")).isEqualTo(true);
        assertThat(content).contains("问题节点");
        assertThat(content).contains("critique");
        assertThat(content).contains("最近事件");
        assertThat(content).contains("建议下一步");
        assertThat(content).contains("degraded-meaning");
        // problem node should appear before aggregate in problem section focus
        assertThat(content.indexOf("critique")).isLessThan(content.indexOf("其他节点"));

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("DEGRADED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problemNodes = (List<Map<String, Object>>) diagnosis.get("problemNodes");
        assertThat(problemNodes).hasSize(1);
        assertThat(problemNodes.get(0).get("nodeName")).isEqualTo("critique");
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) diagnosis.get("suggestedActions");
        assertThat(actions).isNotEmpty();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) response.get("sourceRefs");
        assertThat(refs.stream().anyMatch(r -> "degraded-meaning".equals(r.get("id")))).isTrue();
    }

    @Test
    void diagnoseFailedRunIncludesSuggestedActions() {
        String runId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("daily");
        run.setStatus("FAILED");
        run.setSubject("market");
        run.setErrorMessage("upstream timeout");
        when(workflowService.run(runId)).thenReturn(run);

        WorkflowNodeRun failed = node("news", "news_fetch", "FAILED", "socket timeout", null);
        when(workflowMapper.findNodeRuns(runId)).thenReturn(Arrays.asList(failed));
        when(workflowMapper.findRunEvents(runId)).thenReturn(Arrays.asList(
                event("NODE_FAILED", "news", "socket timeout", "t1")
        ));

        Map<String, Object> response = helpService.diagnoseRun("t1", "执行失败了怎么办", diagnosisIntent(runId));

        assertThat(response.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(response.get("content"))).contains("建议下一步");
        assertThat(String.valueOf(response.get("content"))).contains("news_fetch");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("FAILURE");
    }

    @Test
    void diagnoseInProgressRunDoesNotEscalate() {
        String runId = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("deep_dive");
        run.setStatus("RUNNING");
        run.setSubject("NVDA");
        when(workflowService.run(runId)).thenReturn(run);
        when(workflowMapper.findNodeRuns(runId)).thenReturn(Arrays.asList(
                node("n1", "start", "COMPLETED", null, null)
        ));
        when(workflowMapper.findRunEvents(runId)).thenReturn(CollectionsEmptyEvents());

        Map<String, Object> response = helpService.diagnoseRun("t1", "这个 run 卡住了吗", diagnosisIntent(runId));

        assertThat(response.get("ok")).isEqualTo(true);
        assertThat(response.get("escalateHuman")).isEqualTo(false);
        assertThat(String.valueOf(response.get("content"))).contains("进行中");
        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis.get("focus")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void safetyRefuseApprove() {
        Map<String, Object> response = helpService.safetyRefuse("t1", "直接帮我批准这条推荐", null);
        assertThat(response).isNotNull();
        assertThat(String.valueOf(response.get("content"))).contains("不能代为审批");
    }

    @Test
    void answerFromKnowledgeReturnsPrefilteredHits() {
        HelpIntent.Result intent = HelpIntent.Result.empty();
        intent.setDomain(HelpIntent.Domain.PRODUCT_HELP);
        intent.setRouteAction(HelpIntent.RouteAction.KNOWLEDGE_RAG);

        Map<String, Object> response = helpService.answerFromKnowledge(
                "t1", "如何运行 published 工作流 操作步骤", intent, java.util.Collections.emptyList());

        assertThat(response).isNotNull();
        assertThat(response.get("provider")).isEqualTo("help-knowledge");
        assertThat(response.get("routeAction")).isEqualTo("KNOWLEDGE_RAG");
        assertThat(String.valueOf(response.get("content"))).isNotBlank();
        assertThat(response.get("knowledge")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> knowledge = (Map<String, Object>) response.get("knowledge");
        assertThat(knowledge.get("preFiltered")).isEqualTo(true);
    }

    @Test
    void answerFromHelpLoopEscalatesWhenNoKnowledge() {
        KnowledgeService empty = new KnowledgeService();
        empty.replaceDocumentsForTest(java.util.Collections.emptyList());
        HelpService svc = new HelpService(playbookService, workflowService, workflowMapper, empty, new HelpLoopService(empty));
        HelpIntent.Result intent = HelpIntent.Result.empty();
        intent.setDomain(HelpIntent.Domain.PRODUCT_HELP);
        intent.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);

        Map<String, Object> response = svc.answerFromHelpLoop("t1", "xyzzy-no-hit-999", intent, java.util.Collections.emptyList());

        assertThat(response.get("escalateHuman")).isEqualTo(true);
        assertThat(response.get("ok")).isEqualTo(false);
        assertThat(response.get("helpLoop")).isInstanceOf(Map.class);
    }

    private static HelpIntent.Result diagnosisIntent(String runId) {
        HelpIntent.Result intent = HelpIntent.Result.empty();
        intent.setDomain(HelpIntent.Domain.RUN_DIAGNOSIS);
        intent.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);
        HelpIntent.Entities entities = new HelpIntent.Entities();
        entities.setRunId(runId);
        intent.setEntities(entities);
        return intent;
    }

    private static WorkflowNodeRun node(String id, String name, String status, String error, String outputJson) {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setNodeId(id);
        node.setNodeName(name);
        node.setStatus(status);
        node.setErrorMessage(error);
        node.setOutputJson(outputJson);
        node.setAttempt(1);
        node.setMaxAttempts(3);
        return node;
    }

    private static WorkflowRunEvent event(String type, String nodeId, String message, String createdAt) {
        WorkflowRunEvent event = new WorkflowRunEvent();
        event.setEventType(type);
        event.setNodeId(nodeId);
        event.setMessage(message);
        event.setCreatedAt(createdAt);
        return event;
    }

    private static List<WorkflowRunEvent> CollectionsEmptyEvents() {
        return Arrays.asList();
    }
}
