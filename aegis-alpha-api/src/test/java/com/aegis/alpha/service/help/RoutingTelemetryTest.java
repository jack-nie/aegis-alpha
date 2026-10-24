package com.aegis.alpha.service.help;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.service.IntentRouterService;
import com.aegis.alpha.service.LangChainGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P2-4: one structured routing log per decision, never containing the message text. */
class RoutingTelemetryTest {
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private HelpRoutingService routing;
    private LangChainGateway gateway;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(RoutingTelemetry.LOGGER_NAME);
        appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        WorkflowMapper mapper = mock(WorkflowMapper.class);
        WorkflowDefinition daily = new WorkflowDefinition();
        daily.setWorkflowKey("daily");
        daily.setName("Daily");
        daily.setTriggerKeywords("日报");
        when(mapper.findDefinitions()).thenReturn(Collections.singletonList(daily));
        gateway = mock(LangChainGateway.class);
        PlaybookService playbooks = new PlaybookService();
        playbooks.reload();
        routing = new HelpRoutingService(new IntentRouterService(mapper, gateway), playbooks);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        MDC.remove("traceId");
    }

    @Test
    void llmRoutedMessageLogsFieldsWithoutText() {
        Map<String, Object> llm = new HashMap<String, Object>();
        llm.put("workflowKey", "daily");
        llm.put("confidence", 0.95);
        when(gateway.classifyIntent(anyString(), any())).thenReturn(llm);
        MDC.put("traceId", "trace-123");
        String secret = "请把私密客户张三的研究笔记整理成晨间摘要材料";

        routing.classifyExtended(secret, null);
        routing.classifyExtended(secret, null);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(2);
        String first = events.get(0).getFormattedMessage();
        assertThat(first).startsWith("route ")
                .contains("traceId=trace-123", "domain=RESEARCH", "routeAction=DISPATCH_WORKFLOW",
                        "source=llm", "confidence=0.95", "latencyMs=", "llmUsed=true", "cacheHit=false",
                        "msgLen=" + secret.length(), "msgHash=")
                .doesNotContain("张三").doesNotContain(secret);
        String second = events.get(1).getFormattedMessage();
        assertThat(second).contains("llmUsed=false", "cacheHit=true");
    }

    @Test
    void ruleRoutedMessageDoesNotUseLlm() {
        routing.classifyExtended("你好", null, "t-9");

        String line = appender.list.get(0).getFormattedMessage();
        assertThat(line).contains("traceId=t-9", "llmUsed=false", "cacheHit=false", "msgLen=2")
                .doesNotContain("你好");
    }
}
