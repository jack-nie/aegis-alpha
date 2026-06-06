package com.aegis.alpha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatServiceIntentRoutingTest {

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        /* resolveWorkflowKey is package-private; ChatService needs deps but we only
           call the pure intent method so nulls are safe for the unused collaborators. */
        chatService = new ChatService(null, null, null, null, null);
    }

    /* ---- explicit workflowKey override ---- */

    @Test
    void explicitValidKeyIsUsed() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowKey", "deep_dive");
        assertEquals("deep_dive", chatService.resolveWorkflowKey(body, "hello"));
    }

    @Test
    void explicitUnknownKeyIsIgnored() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowKey", "nonexistent");
        assertNull(chatService.resolveWorkflowKey(body, "hello"));
    }

    /* ---- keyword routing ---- */

    @ParameterizedTest
    @CsvSource({
            "\u6bcf\u65e5\u884c\u60c5\u6982\u89c8, daily",
            "\u7ed9\u6211\u4e00\u4efd\u65e5\u62a5, daily",
            "\u76d8\u524d\u901f\u89c8, daily",
            "daily morning briefing, daily",
            "show me the daily graph, daily",
    })
    void routesDaily(String message, String expectedKey) {
        assertEquals(expectedKey, chatService.resolveWorkflowKey(null, message));
    }

    @ParameterizedTest
    @CsvSource({
            "\u6df1\u5ea6\u5206\u6790\u82f1\u4f1f\u8fbe, deep_dive",
            "\u5bf9\u8fd9\u53ea\u80a1\u505a\u4e2a\u6df1\u5165\u7814\u7a76, deep_dive",
            "deep dive on NVDA, deep_dive",
    })
    void routesDeepDive(String message, String expectedKey) {
        assertEquals(expectedKey, chatService.resolveWorkflowKey(null, message));
    }

    @ParameterizedTest
    @CsvSource({
            "\u6211\u60f3\u6b62\u635f\u5356\u51fa, exit_workflow",
            "\u5e2e\u6211\u5e73\u4ed3 TSLA, exit_workflow",
            "set stop loss for AAPL, exit_workflow",
            "take profit now, exit_workflow",
    })
    void routesExit(String message, String expectedKey) {
        assertEquals(expectedKey, chatService.resolveWorkflowKey(null, message));
    }

    @ParameterizedTest
    @CsvSource({
            "\u5206\u6790\u6211\u7684\u6295\u8d44\u7ec4\u5408, portfolio_workflow",
            "portfolio asset allocation, portfolio_workflow",
    })
    void routesPortfolio(String message, String expectedKey) {
        assertEquals(expectedKey, chatService.resolveWorkflowKey(null, message));
    }

    @ParameterizedTest
    @CsvSource({
            "\u5e2e\u6211\u8c03\u6574\u4ed3\u4f4d, position_workflow",
            "\u5206\u6790\u5f53\u524d\u6301\u4ed3, position_workflow",
            "position sizing for QQQ, position_workflow",
    })
    void routesPosition(String message, String expectedKey) {
        assertEquals(expectedKey, chatService.resolveWorkflowKey(null, message));
    }

    @ParameterizedTest
    @CsvSource({
            "\u534a\u5bfc\u4f53\u884c\u4e1a\u5206\u6790, sector-analyst-workflow",
            "\u54ea\u4e9b\u677f\u5757\u503c\u5f97\u5173\u6ce8, sector-analyst-workflow",
            "sector analyst for tech, sector-analyst-workflow",
    })
    void routesSector(String message, String expectedKey) {
        assertEquals(expectedKey, chatService.resolveWorkflowKey(null, message));
    }

    /* ---- no match -> null (copilot fallback) ---- */

    @Test
    void unmatchedMessageReturnsNull() {
        assertNull(chatService.resolveWorkflowKey(null, "hello, how are you?"));
    }

    @Test
    void nullMessageReturnsNull() {
        assertNull(chatService.resolveWorkflowKey(null, null));
    }
}
