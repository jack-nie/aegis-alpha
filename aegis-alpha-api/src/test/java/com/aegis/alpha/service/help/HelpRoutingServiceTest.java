package com.aegis.alpha.service.help;

import com.aegis.alpha.service.IntentRouterService;
import com.aegis.alpha.service.LangChainGateway;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.domain.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HelpRoutingServiceTest {

    private IntentRouterService intentRouterService;
    private PlaybookService playbookService;
    private HelpRoutingService routing;

    @BeforeEach
    void setUp() {
        WorkflowMapper workflowMapper = mock(WorkflowMapper.class);
        LangChainGateway gateway = mock(LangChainGateway.class);
        when(gateway.classifyIntent(any(), any())).thenThrow(new RuntimeException("no llm"));
        when(workflowMapper.findDefinitions()).thenReturn(Arrays.asList(
                def("daily", "Daily", "daily,日报,盘前"),
                def("deep_dive", "Deep Dive", "deep dive,深度分析")
        ));
        intentRouterService = new IntentRouterService(workflowMapper, gateway);

        playbookService = new PlaybookService();
        // inject parsed playbooks without Spring resources
        playbookService.reload();
        // Ensure at least degraded playbook for unit test via parse
        if (playbookService.list().isEmpty()) {
            // reload from classpath resources in test runtime if packaged; else register manually
        }
        routing = new HelpRoutingService(intentRouterService, playbookService);
    }

    private WorkflowDefinition def(String key, String name, String keywords) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setWorkflowKey(key);
        d.setName(name);
        d.setTriggerKeywords(keywords);
        d.setRoutingDescription("");
        return d;
    }

    @Test
    void researchWorkflowTakesPriority() {
        HelpIntent.Result result = routing.classifyExtended("给我一份盘前日报", null);
        assertThat(result.getDomain()).isEqualTo(HelpIntent.Domain.RESEARCH);
        assertThat(result.getRouteAction()).isEqualTo(HelpIntent.RouteAction.DISPATCH_WORKFLOW);
        assertThat(result.getWorkflowKey()).isEqualTo("daily");
        assertThat(result.isCacheable()).isFalse();
    }

    @Test
    void diagnosisWithoutRunIdClarifies() {
        HelpIntent.Result result = routing.classifyExtended("这个 run 为什么失败了？", null);
        assertThat(result.getDomain()).isEqualTo(HelpIntent.Domain.RUN_DIAGNOSIS);
        assertThat(result.getRouteAction()).isEqualTo(HelpIntent.RouteAction.CLARIFY);
        assertThat(result.isNeedsClarification()).isTrue();
        assertThat(result.isCacheable()).isFalse();
    }

    @Test
    void diagnosisWithPageContextRunId() {
        HelpIntent.PageContext page = HelpIntent.PageContext.fromMap(
                Collections.singletonMap("runId", "11111111-2222-3333-4444-555555555555")
        );
        HelpIntent.Result result = routing.classifyExtended("为什么 degraded？", page);
        assertThat(result.getDomain()).isEqualTo(HelpIntent.Domain.RUN_DIAGNOSIS);
        assertThat(result.getRouteAction()).isEqualTo(HelpIntent.RouteAction.HELP_GRAPH);
        assertThat(result.getEntities().getRunId()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(result.isCacheable()).isFalse();
    }

    @Test
    void extractsRunIdFromMessage() {
        HelpIntent.Result result = routing.classifyExtended(
                "帮我看看 run 11111111-2222-3333-4444-555555555555 节点失败原因", null);
        assertThat(result.getRouteAction()).isEqualTo(HelpIntent.RouteAction.HELP_GRAPH);
        assertThat(result.getEntities().getRunId()).isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @ParameterizedTest
    @CsvSource({
            "什么是 DEGRADED？, PRODUCT_HELP, PLAYBOOK",
            "如何打开 AI Copilot？, PRODUCT_HELP, PLAYBOOK",
            "推荐审批在哪里做？, PRODUCT_HELP, PLAYBOOK",
            "怎么运行一个工作流？, PRODUCT_HELP, PLAYBOOK",
            "运行中心在哪看节点输出？, PRODUCT_HELP, PLAYBOOK",
            "什么是 published workflow？, PRODUCT_HELP, PLAYBOOK"
    })
    void productHelpRoutesToPlaybook(String message, String domain, String action) {
        // Ensure playbooks loaded from test classpath resources
        playbookService.reload();
        HelpIntent.Result result = routing.classifyExtended(message, null);
        assertThat(result.getDomain().name()).isEqualTo(domain);
        assertThat(result.getRouteAction().name()).isEqualTo(action);
    }

    @Test
    void chitchatFallsToCopilot() {
        HelpIntent.Result result = routing.classifyExtended("你好", null);
        assertThat(result.getDomain()).isEqualTo(HelpIntent.Domain.CHITCHAT);
        assertThat(result.getRouteAction()).isEqualTo(HelpIntent.RouteAction.COPILOT);
    }

    @Test
    void privateEntitiesDisableCache() {
        HelpIntent.Entities entities = new HelpIntent.Entities();
        entities.setRunId("11111111-2222-3333-4444-555555555555");
        assertThat(routing.isCacheable("任意问题", entities)).isFalse();
        assertThat(routing.isCacheable("查看我的仓位", new HelpIntent.Entities())).isFalse();
        assertThat(routing.isCacheable("什么是 DEGRADED", new HelpIntent.Entities())).isTrue();
    }
}
