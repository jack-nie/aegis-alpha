package com.aegis.alpha.controller;

import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.help.tool.HelpToolCatalog;
import com.aegis.alpha.service.help.tool.HelpToolDefinition;
import com.aegis.alpha.service.help.tool.HelpToolGateway;
import com.aegis.alpha.service.help.trace.HelpTrace;
import com.aegis.alpha.service.help.trace.HelpTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1/P2 endpoint tests: both diagnostics endpoints are authenticated and expose
 * aggregate data only.
 */
class HelpEndpointsTest {

    private AuthService authService;
    private HelpTraceService traceService;
    private HelpToolGateway gateway;
    private HelpMetricsController metricsController;
    private HelpToolsController toolsController;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        HelpToolCatalog catalog = new HelpToolCatalog();
        traceService = new HelpTraceService();
        gateway = new HelpToolGateway(catalog);
        metricsController = new HelpMetricsController(authService, traceService, gateway);
        toolsController = new HelpToolsController(authService, catalog, gateway);

        Map<String, Object> me = new LinkedHashMap<String, Object>();
        me.put("user_id", "u-1");
        when(authService.me("Bearer ok")).thenReturn(me);
        when(authService.me(null)).thenReturn(null);
        when(authService.me("")).thenReturn(null);
    }

    @Test
    void metricsEndpointRequiresAuthentication() {
        assertThat(metricsController.metrics(null).getStatusCodeValue()).isEqualTo(401);
        assertThat(metricsController.metrics("").getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    void metricsEndpointReturnsHelpAndToolAggregates() {
        HelpTrace trace = traceService.start("trace-1", "thread-1");
        traceService.markRoute(trace, "PRODUCT_HELP", "PLAYBOOK");
        traceService.markProvider(trace, "help-playbook");
        traceService.finish(trace);
        gateway.execute(HelpToolCatalog.GET_WORKFLOW_RUN, "u-1",
                () -> Collections.<String, Object>singletonMap("run", "ok"));

        Map<String, Object> body = metricsController.metrics("Bearer ok").getBody();

        assertThat(body).containsKeys("help", "tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> help = (Map<String, Object>) body.get("help");
        assertThat(help.get("total")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> tools = (Map<String, Object>) body.get("tools");
        assertThat(tools.get("calls")).isEqualTo(1L);
    }

    @Test
    void toolsEndpointRequiresAuthenticationAndListsOnlyReadOnlyTools() {
        assertThat(toolsController.tools(null).getStatusCodeValue()).isEqualTo(401);

        Map<String, Object> body = toolsController.tools("Bearer ok").getBody();
        assertThat(body).containsKeys("tools", "audit", "metrics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertThat(tools).hasSize(6);
        for (Map<String, Object> tool : tools) {
            assertThat(tool.get("readOnly")).isEqualTo(true);
            assertThat(String.valueOf(tool.get("name")))
                    .doesNotContain("approve")
                    .doesNotContain("reject")
                    .doesNotContain("write")
                    .doesNotContain("sql");
        }
    }

    @Test
    void catalogStillExposesSixDefinitions() {
        List<HelpToolDefinition> all = new HelpToolCatalog().all();
        assertThat(all).hasSize(6);
        assertThat(all).allMatch(HelpToolDefinition::isReadOnly);
    }
}
