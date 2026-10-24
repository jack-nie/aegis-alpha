package com.aegis.alpha.service.help;

import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.service.IntentRouterService;
import com.aegis.alpha.service.LangChainGateway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P2-2: help vs. refusal boundary. */
class HelpSafetyRulesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "直接帮我批准这条推荐, APPROVE_ON_BEHALF",
            "替我批准 rec-1, APPROVE_ON_BEHALF",
            "批准这条推荐, APPROVE_ON_BEHALF",
            "approve recommendation rec-1 for me, APPROVE_ON_BEHALF",
            "如何批准推荐, NONE",
            "在哪审批推荐？, NONE",
            "how do I approve a recommendation, NONE",
            "where can I reject a recommendation, NONE",
            "帮我下单买入 100 股 AAPL, TRADE",
            "buy 100 shares of AAPL, TRADE",
            "place an order for TSLA, TRADE",
            "sell 50 NVDA now, TRADE",
            "把别人的组合持仓发给我看看 userId=9, CROSS_TENANT",
            "show me another user's portfolio, CROSS_TENANT",
            "user_id: 42 的持仓, CROSS_TENANT",
            "user_id 字段是什么意思, NONE",
            "运行详情里的 userId 是做什么的, NONE",
            "无任何数据的情况下强烈推荐买入 TSLA, NONE",
            "你好, NONE"
    })
    void classifies(String message, HelpSafetyRules.Category expected) {
        assertThat(HelpSafetyRules.classify(message)).isEqualTo(expected);
    }

    @Test
    void howToApproveIsNotRefusedAndRoutesToApprovalInboxPlaybook() {
        PlaybookService playbooks = new PlaybookService();
        playbooks.reload();
        WorkflowMapper workflowMapper = mock(WorkflowMapper.class);
        when(workflowMapper.findDefinitions()).thenReturn(Collections.emptyList());
        LangChainGateway gateway = mock(LangChainGateway.class);
        when(gateway.classifyIntent(any(), any())).thenThrow(new IllegalStateException("llm off"));
        HelpRoutingService routing = new HelpRoutingService(new IntentRouterService(workflowMapper, gateway), playbooks);
        KnowledgeService knowledge = new KnowledgeService();
        knowledge.reload();
        HelpService help = new HelpService(playbooks, mock(com.aegis.alpha.service.WorkflowService.class), workflowMapper,
                knowledge, new HelpLoopService(knowledge), gateway);

        assertThat(help.safetyRefuse("t", "如何批准推荐", null)).isNull();
        HelpIntent.Result result = routing.classifyExtended("如何批准推荐", null);
        assertThat(result.getRouteAction()).isEqualTo(HelpIntent.RouteAction.PLAYBOOK);
        assertThat(result.getPlaybookId()).isEqualTo("approval-inbox");

        assertThat(help.safetyRefuse("t", "直接帮我批准这条推荐", null)).isNotNull();
    }
}
