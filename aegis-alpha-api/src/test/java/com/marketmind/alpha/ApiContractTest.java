package com.marketmind.alpha;

import com.marketmind.alpha.domain.User;
import com.marketmind.alpha.domain.WorkflowDefinition;
import com.marketmind.alpha.mapper.DashboardMapper;
import com.marketmind.alpha.mapper.UserMapper;
import com.marketmind.alpha.mapper.WorkflowMapper;
import com.marketmind.alpha.service.AuthService;
import com.marketmind.alpha.service.LangChainGateway;
import com.marketmind.alpha.service.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkflowMapper workflowMapper;
    @Autowired
    private DashboardMapper dashboardMapper;
    @MockBean
    private LangChainGateway langChainGateway;
    @MockBean
    private MarketDataService marketDataService;

    @BeforeEach
    void seed() {
        Map<String, Object> langChainResult = new LinkedHashMap<>();
        langChainResult.put("ok", true);
        langChainResult.put("summary", "Mock LangChain result");
        langChainResult.put("message", "Mock LangChain result");
        langChainResult.put("content", "Mock LangChain result");
        when(langChainGateway.runAgent(any(), anyMap(), anyMap(), anyString())).thenReturn(langChainResult);
        when(langChainGateway.executeNode(any(), anyMap(), anyMap(), anyString())).thenReturn(langChainResult);
        when(marketDataService.quote(anyString())).thenReturn(marketQuote());
        when(marketDataService.financials(anyString())).thenReturn(marketFinancials());
        when(marketDataService.news(anyString())).thenReturn(marketNews());
        when(marketDataService.overview(anyString())).thenReturn(marketOverview());

        if (userMapper.count() == 0) {
            User user = new User();
            user.setUserId("u-1");
            user.setUsername("guanghui.nie");
            user.setPasswordHash(AuthService.hash("guanghui.nie"));
            user.setTenantId("tenant-1");
            user.setRoles("portfolio_manager");
            userMapper.insert(user);
        }
        if (workflowMapper.countDefinitions() == 0) {
            WorkflowDefinition workflow = new WorkflowDefinition();
            workflow.setWorkflowKey("daily");
            workflow.setName("Daily Graph");
            workflow.setVersion(1);
            workflow.setNodes(7);
            workflow.setEdges(6);
            workflowMapper.insertDefinition(workflow);
            WorkflowDefinition stockAnalysis = new WorkflowDefinition();
            stockAnalysis.setWorkflowKey("stock_analysis");
            stockAnalysis.setName("Stock Analysis");
            stockAnalysis.setVersion(1);
            stockAnalysis.setNodes(9);
            stockAnalysis.setEdges(10);
            workflowMapper.insertDefinition(stockAnalysis);
        }
        if (workflowMapper.findDefinition("stock_analysis") == null) {
            WorkflowDefinition sa = new WorkflowDefinition();
            sa.setWorkflowKey("stock_analysis");
            sa.setName("Stock Analysis");
            sa.setVersion(1);
            sa.setNodes(9);
            sa.setEdges(10);
            workflowMapper.insertDefinition(sa);
        }
        if (dashboardMapper.countQuadrant() == 0) {
            dashboardMapper.insertQuadrant("2025-03-01", 3, 4, 1, 4);
            dashboardMapper.insertCredit("2022-03", "-5.90% / R27.8", "-14.60% / R5.6", "-26.30% / R2.8", "-19.40% / R2.8");
            dashboardMapper.insertIndicator("VIX", "18.71", "^VIX | Range 10 - 50");
            dashboardMapper.insertMarket("SPY", "S&P 500", new BigDecimal("0.77"));
        }
    }

    @Test
    void marketDataContractExposesLiveProviderPayloads() throws Exception {
        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        mockMvc.perform(get("/_backend/market-data/overview?symbol=AAPL").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.quote.provider").value("yahoo-chart"))
                .andExpect(jsonPath("$.quote.asOfLocal", notNullValue()))
                .andExpect(jsonPath("$.quote.timezone").value("Asia/Hong_Kong"))
                .andExpect(jsonPath("$.quote.delayHint", notNullValue()))
                .andExpect(jsonPath("$.financials.provider").value("sec-companyfacts"))
                .andExpect(jsonPath("$.financials.asOfLocal", notNullValue()))
                .andExpect(jsonPath("$.financials.timezone").value("Asia/Hong_Kong"))
                .andExpect(jsonPath("$.financials.metrics[0].label").value("Revenue"))
                .andExpect(jsonPath("$.news.provider").value("yahoo-finance-rss"));

        mockMvc.perform(post("/_backend/workflow-nodes/execute").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"functionName\":\"fdb.daily_ohlc\",\"params\":{\"symbol\":\"AAPL\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functionName").value("fdb.daily_ohlc"))
                .andExpect(jsonPath("$.provider").value("yahoo-chart"))
                .andExpect(jsonPath("$.rows[0].symbol").value("AAPL"));

        mockMvc.perform(post("/_backend/workflow-nodes/execute").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"functionName\":\"fdb.fundamental_data\",\"params\":{\"symbol\":\"AAPL\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functionName").value("fdb.fundamental_data"))
                .andExpect(jsonPath("$.provider").value("sec-companyfacts"))
                .andExpect(jsonPath("$.rows[0].metric").value("Revenues"));

        mockMvc.perform(post("/_backend/workflow-nodes/execute").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"functionName\":\"fdb.global_news\",\"params\":{\"symbol\":\"AAPL\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functionName").value("fdb.global_news"))
                .andExpect(jsonPath("$.provider").value("yahoo-finance-rss"))
                .andExpect(jsonPath("$.rows[0].title").value("Apple updates guidance"));

        mockMvc.perform(post("/_backend/workflow-nodes/execute").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"functionName\":\"general.agent\",\"action\":\"hydrate_market_data\",\"params\":{\"symbol\":\"AAPL\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functionName").value("general.agent"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.overview.symbol").value("AAPL"))
                .andExpect(jsonPath("$.quote.provider").value("yahoo-chart"))
                .andExpect(jsonPath("$.financials.provider").value("sec-companyfacts"))
                .andExpect(jsonPath("$.news.provider").value("yahoo-finance-rss"));
    }

    @Test
    void frontendApiContractWorksEndToEnd() throws Exception {
        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        mockMvc.perform(get("/_backend/auth/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("guanghui.nie"));

        mockMvc.perform(get("/_backend/dashboard").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quadrantRows", hasSize(1)))
                .andExpect(jsonPath("$.markets", hasSize(1)));

        mockMvc.perform(get("/_backend/workflows").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowKey").value("daily"));

        mockMvc.perform(put("/_backend/workflows/daily/layout").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodes\":[" +
                                "{\"id\":\"Start\",\"type\":\"workflowNode\",\"position\":{\"x\":60,\"y\":360},\"data\":{\"nodeType\":\"start\"}}," +
                                "{\"id\":\"End\",\"type\":\"workflowNode\",\"position\":{\"x\":260,\"y\":360},\"data\":{\"nodeType\":\"end\"}}" +
                                "],\"edges\":[{\"source\":\"Start\",\"target\":\"End\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowKey").value("daily"))
                .andExpect(jsonPath("$.nodes", hasSize(2)));

        mockMvc.perform(get("/_backend/workflows/daily/layout").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowKey").value("daily"))
                .andExpect(jsonPath("$.nodes", hasSize(2)));

        mockMvc.perform(post("/_backend/workflow-nodes/execute").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"functionName\":\"fdb.classification_data\",\"action\":\"get_classification_tree\",\"params\":\"{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functionName").value("fdb.classification_data"))
                .andExpect(jsonPath("$.action").value("get_classification_tree"))
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/_backend/dify/workflows/daily/dsl").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowKey").value("daily"))
                .andExpect(jsonPath("$.yaml", notNullValue()));

        mockMvc.perform(post("/_backend/dify/workflows/daily/publish").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.yaml", notNullValue()));

        mockMvc.perform(post("/_backend/portfolio/portfolios").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Core Income Portfolio\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Core Income Portfolio"));

        mockMvc.perform(post("/_backend/portfolio/trades").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portfolioId\":\"core\",\"tradeDate\":\"2026-05-04\",\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":10,\"price\":185.25,\"fee\":1.25,\"commission\":0.75,\"netAmount\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.side").value("BUY"))
                .andExpect(jsonPath("$.grossAmount").value(1852.5))
                .andExpect(jsonPath("$.netAmount").value(1854.5))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"));

        mockMvc.perform(post("/_backend/portfolio/trades/import").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"importBatchId\":\"batch-1\",\"rows\":[" +
                                "{\"portfolioId\":\"core\",\"tradeDate\":\"2026-05-05\",\"symbol\":\"MSFT\",\"side\":\"SELL\",\"quantity\":3,\"price\":420,\"fee\":2,\"tax\":1,\"commission\":1,\"netAmount\":999999}," +
                                "{\"portfolioId\":\"core\",\"tradeDate\":\"2026-05-06\",\"symbol\":\"NVDA\",\"side\":\"BUY\",\"quantity\":2,\"price\":880,\"otherFee\":5}" +
                                "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sourceType").value("IMPORT"))
                .andExpect(jsonPath("$[0].importBatchId").value("batch-1"))
                .andExpect(jsonPath("$[0].netAmount").value(1256.0));

        mockMvc.perform(get("/_backend/portfolio/trades").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.portfolioId == 'core')]", hasSize(3)));

        mockMvc.perform(get("/_backend/dashboard").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts", notNullValue()));

        mockMvc.perform(post("/_backend/backtest/history").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runName\":\"ARCC income backtest\",\"strategy\":\"Dividend Carry\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.strategy").value("Dividend Carry"));

        mockMvc.perform(post("/_backend/chat/messages").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"帮我分析AI行业\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    void portfolioDetailContractsExposeSummaryPositionsAndTrades() throws Exception {
        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        String created = mockMvc.perform(post("/_backend/portfolio/portfolios").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P2 Contract Portfolio\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("P2 Contract Portfolio"))
                .andReturn().getResponse().getContentAsString();
        String portfolioId = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/_backend/portfolio/" + portfolioId + "/summary").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolioId").value(portfolioId))
                .andExpect(jsonPath("$.asOf", notNullValue()))
                .andExpect(jsonPath("$.sourceStatus").value("摘要已导入，交易与持仓明细待同步"))
                .andExpect(jsonPath("$.dataCompleteness").value("SEEDED_SUMMARY_ONLY"))
                .andExpect(jsonPath("$.summary.name").value("P2 Contract Portfolio"));

        mockMvc.perform(get("/_backend/portfolio/" + portfolioId + "/positions").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolioId").value(portfolioId))
                .andExpect(jsonPath("$.dataCompleteness").value("SEEDED_SUMMARY_ONLY"))
                .andExpect(jsonPath("$.positions", hasSize(0)));

        mockMvc.perform(post("/_backend/portfolio/trades").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portfolioId\":\"" + portfolioId + "\",\"tradeDate\":\"2026-05-04\",\"symbol\":\"MSFT\",\"side\":\"BUY\",\"quantity\":10,\"price\":400,\"fee\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/_backend/portfolio/" + portfolioId + "/positions").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataCompleteness").value("DETAILS_SYNCED"))
                .andExpect(jsonPath("$.positions", hasSize(1)))
                .andExpect(jsonPath("$.positions[0].symbol").value("MSFT"))
                .andExpect(jsonPath("$.positions[0].quantity").value(10));

        mockMvc.perform(get("/_backend/portfolio/" + portfolioId + "/trades").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolioId").value(portfolioId))
                .andExpect(jsonPath("$.dataCompleteness").value("DETAILS_SYNCED"))
                .andExpect(jsonPath("$.trades", hasSize(1)))
                .andExpect(jsonPath("$.trades[0].symbol").value("MSFT"));
    }

    @Test
    void chatCopilotUsesAgentNodeInsteadOfLogicFallback() throws Exception {
        when(langChainGateway.runAgent(any(), anyMap(), anyMap(), anyString())).thenAnswer(invocation -> {
            Map<String, Object> node = invocation.getArgument(2);
            Map<String, Object> data = objectMap(node.get("data"));
            String handler = String.valueOf(data.get("handler"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("provider", "mock");
            result.put("content", "handler=" + handler);
            result.put("message", "handler=" + handler);
            return result;
        });

        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        mockMvc.perform(post("/_backend/chat/messages").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"检查对话窗口输出\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("handler=general.agent"));
    }

    @Test
    void chatCopilotHydratesMarketDataWhenTickerIsMentioned() throws Exception {
        when(langChainGateway.runAgent(any(), anyMap(), anyMap(), anyString())).thenAnswer(invocation -> {
            Map<String, Object> state = invocation.getArgument(1);
            Map<String, Object> overview = objectMap(state.get("marketDataOverview"));
            String subject = invocation.getArgument(3);
            String content = state.get("ticker") + ":" + overview.get("symbol") + ":" + subject;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("provider", "mock");
            result.put("content", content);
            result.put("message", content);
            return result;
        });

        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        mockMvc.perform(post("/_backend/chat/messages").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Analyze AAPL with live quote, news, and financials\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("AAPL:AAPL:AAPL"));
    }

    @Test
    void workflowRuntimeRejectsInvalidLayoutsAndExposesEventsAndIdempotentRuns() throws Exception {
        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        mockMvc.perform(post("/_backend/workflows").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowKey\":\"runtime-foundation\",\"name\":\"Runtime Foundation\",\"engine\":\"langgraph\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/_backend/workflows/runtime-foundation/layout").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodes\":[" +
                                "{\"id\":\"start\",\"type\":\"workflowNode\",\"data\":{\"nodeType\":\"start\"}}," +
                                "{\"id\":\"end\",\"type\":\"workflowNode\",\"data\":{\"nodeType\":\"end\"}}" +
                                "],\"edges\":[{\"source\":\"start\",\"target\":\"missing\"}]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("unknown target")));

        String validLayout = "{\"nodes\":[" +
                "{\"id\":\"start\",\"type\":\"workflowNode\",\"data\":{\"label\":\"Start\",\"nodeType\":\"start\",\"handler\":\"scheduler.manual\"}}," +
                "{\"id\":\"end\",\"type\":\"workflowNode\",\"data\":{\"label\":\"End\",\"nodeType\":\"end\",\"handler\":\"workflow.end\"}}" +
                "],\"edges\":[{\"source\":\"start\",\"target\":\"end\"}]}";

        mockMvc.perform(put("/_backend/workflows/runtime-foundation/layout").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validLayout))
                .andExpect(status().isOk());

        String firstRun = mockMvc.perform(post("/_backend/workflows/runtime-foundation/run")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "runtime-foundation-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"foundation\",\"inputs\":{\"ticker\":\"AAPL\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.idempotencyKey").value("runtime-foundation-key"))
                .andReturn().getResponse().getContentAsString();
        String runId = firstRun.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/_backend/workflows/runtime-foundation/run")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "runtime-foundation-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"foundation\",\"inputs\":{\"ticker\":\"AAPL\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId));

        mockMvc.perform(get("/_backend/workflow/runs/" + runId + "/events").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[0].eventType").value("RUN_CREATED"))
                .andExpect(jsonPath("$[1].eventType").value("NODE_STARTED"))
                .andExpect(jsonPath("$[2].eventType").value("NODE_COMPLETED"))
                .andExpect(jsonPath("$[5].eventType").value("RUN_COMPLETED"));
    }

    @Test
    void durableRuntimePublishesQueuesControlsDispatchesAndRetriesRuns() throws Exception {
        AtomicInteger flakyAttempts = new AtomicInteger();
        when(langChainGateway.executeNode(any(), anyMap(), anyMap(), anyString())).thenAnswer(invocation -> {
            Map<String, Object> node = invocation.getArgument(2);
            if ("flaky".equals(node.get("id")) && flakyAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient model failure");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("summary", "Recovered model result");
            result.put("message", "Recovered model result");
            return result;
        });

        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        mockMvc.perform(post("/_backend/workflows").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowKey\":\"phase2-durable\",\"name\":\"Phase 2 Durable\",\"engine\":\"langgraph\"}"))
                .andExpect(status().isCreated());

        String layout = "{\"nodes\":[" +
                "{\"id\":\"start\",\"type\":\"workflowNode\",\"data\":{\"label\":\"Start\",\"nodeType\":\"start\",\"handler\":\"scheduler.manual\"}}," +
                "{\"id\":\"flaky\",\"type\":\"workflowNode\",\"data\":{\"label\":\"Flaky Agent\",\"nodeType\":\"agent\",\"handler\":\"general.agent\",\"retryPolicy\":{\"maxAttempts\":2,\"backoffMs\":0}}}," +
                "{\"id\":\"end\",\"type\":\"workflowNode\",\"data\":{\"label\":\"End\",\"nodeType\":\"end\",\"handler\":\"workflow.end\"}}" +
                "],\"edges\":[{\"source\":\"start\",\"target\":\"flaky\"},{\"source\":\"flaky\",\"target\":\"end\"}]}";

        mockMvc.perform(put("/_backend/workflows/phase2-durable/layout").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(layout))
                .andExpect(status().isOk());

        String versionJson = mockMvc.perform(post("/_backend/workflows/phase2-durable/publish-version").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowKey").value("phase2-durable"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.layoutJson", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String versionId = versionJson.replaceAll(".*\"versionId\":\"([^\"]+)\".*", "$1");

        String queuedJson = mockMvc.perform(post("/_backend/workflows/phase2-durable/run?async=true")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"queued-control\",\"inputs\":{\"ticker\":\"MSFT\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.workflowVersionId").value(versionId))
                .andReturn().getResponse().getContentAsString();
        String queuedRunId = queuedJson.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/_backend/workflow/runs").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.runId == '" + queuedRunId + "')].availableActions[0]", hasItem("dispatch")))
                .andExpect(jsonPath("$[?(@.runId == '" + queuedRunId + "')].availableActions[1]", hasItem("cancel")))
                .andExpect(jsonPath("$[?(@.runId == '" + queuedRunId + "')].actionReasons.dispatch", hasItem("")))
                .andExpect(jsonPath("$[?(@.runId == '" + queuedRunId + "')].lastEvent.eventType", hasItem("RUN_CREATED")))
                .andExpect(jsonPath("$[?(@.runId == '" + queuedRunId + "')].priority", hasItem("normal")));

        mockMvc.perform(get("/_backend/workflow/runs/" + queuedRunId + "/nodes").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(post("/_backend/workflow/runs/" + queuedRunId + "/pause").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        mockMvc.perform(post("/_backend/workflow/runs/" + queuedRunId + "/resume").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        mockMvc.perform(post("/_backend/workflow/runs/" + queuedRunId + "/cancel").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        String runnableJson = mockMvc.perform(post("/_backend/workflows/phase2-durable/run?async=true")
                        .header("Authorization", auth)
                        .header("Idempotency-Key", "phase2-dispatch-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"dispatch-retry\",\"inputs\":{\"ticker\":\"MSFT\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String runnableRunId = runnableJson.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/_backend/workflow/runs/" + runnableRunId + "/dispatch").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.nodeCount").value(3));

        mockMvc.perform(get("/_backend/workflow/runs/" + runnableRunId + "/nodes").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[1].nodeId").value("flaky"))
                .andExpect(jsonPath("$[1].attempt").value(1))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[2].nodeId").value("flaky"))
                .andExpect(jsonPath("$[2].attempt").value(2))
                .andExpect(jsonPath("$[2].status").value("COMPLETED"));

        mockMvc.perform(get("/_backend/workflow/runs/" + runnableRunId + "/events").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("RUN_CREATED"))
                .andExpect(jsonPath("$[?(@.eventType == 'NODE_RETRYING')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.eventType == 'RUN_COMPLETED')]", hasSize(1)));
    }

    @Test
    void governanceMaterializesModelEvidenceAndRecommendationReview() throws Exception {
        when(langChainGateway.executeNode(any(), anyMap(), anyMap(), anyString())).thenAnswer(invocation -> {
            Map<String, Object> node = invocation.getArgument(2);
            Map<String, Object> data = objectMap(node.get("data"));
            String handler = data == null ? "" : String.valueOf(data.get("handler"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("provider", "langchain-openai");
            result.put("model", "deepseek-v4-flash");
            result.put("handler", handler);
            result.put("summary", handler.contains("stock_recommendation") ? "BUY with review: durable demand, positive margins, monitor valuation risk." : "Governed evidence for " + handler);
            result.put("confidence", handler.contains("stock_recommendation") ? 0.74 : 0.61);
            result.put("signals", Arrays.asList(source("Revenue growth signal", "signal")));
            result.put("sources", Arrays.asList(source("SEC CompanyFacts", "filing"), source("Yahoo Finance RSS", "news")));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("usage", usage(120, 80));
            result.put("data", payload);
            return result;
        });

        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        mockMvc.perform(get("/_backend/governance/models").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("openai"))
                .andExpect(jsonPath("$[0].modelName").value("deepseek-v4-flash"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        mockMvc.perform(get("/_backend/recommendations").header("Authorization", auth))
                .andExpect(status().isOk());

        mockMvc.perform(post("/_backend/workflows").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowKey\":\"phase3-governed\",\"name\":\"Phase 3 Governed\",\"engine\":\"langgraph\"}"))
                .andExpect(status().isCreated());

        String layout = "{" +
                "\"nodes\":[" +
                "{\"id\":\"start\",\"type\":\"workflowNode\",\"data\":{\"label\":\"Start\",\"nodeType\":\"start\",\"handler\":\"scheduler.manual\"}}," +
                "{\"id\":\"financials\",\"type\":\"workflowNode\",\"data\":{\"label\":\"Financial Interpretation\",\"nodeType\":\"agent\",\"handler\":\"finance.financial_interpretation\",\"outputKeys\":[\"financial_interpretation\"]}}," +
                "{\"id\":\"recommend\",\"type\":\"workflowNode\",\"data\":{\"label\":\"Recommendation Aggregate\",\"nodeType\":\"agent\",\"handler\":\"finance.stock_recommendation_aggregate\",\"outputKeys\":[\"stock_recommendation\"]}}," +
                "{\"id\":\"end\",\"type\":\"workflowNode\",\"data\":{\"label\":\"End\",\"nodeType\":\"end\",\"handler\":\"workflow.end\"}}" +
                "]," +
                "\"edges\":[{\"source\":\"start\",\"target\":\"financials\"},{\"source\":\"financials\",\"target\":\"recommend\"},{\"source\":\"recommend\",\"target\":\"end\"}]}";

        mockMvc.perform(put("/_backend/workflows/phase3-governed/layout").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(layout))
                .andExpect(status().isOk());

        String runJson = mockMvc.perform(post("/_backend/workflows/phase3-governed/run").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"governed recommendation\",\"inputs\":{\"ticker\":\"AAPL\",\"timeHorizon\":\"6-12 months\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        String runId = runJson.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/_backend/governance/llm-calls?workflowRunId=" + runId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].totalTokens").value(200));

        mockMvc.perform(get("/_backend/recommendations/" + runId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.workflowRunId").value(runId))
                .andExpect(jsonPath("$.recommendation.symbol").value("AAPL"))
                .andExpect(jsonPath("$.recommendation.recommendation").value("BUY"))
                .andExpect(jsonPath("$.recommendation.approvalStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.recommendation.disclaimer", containsString("not investment advice")))
                .andExpect(jsonPath("$.evidence", hasSize(4)));

        mockMvc.perform(post("/_backend/recommendations/" + runId + "/approve").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"));

        mockMvc.perform(post("/_backend/recommendations/" + runId + "/reject").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("REJECTED"));
    }

    @Test
    void stockRecommendationWorkflowCreatesTraceableBacktestHistory() throws Exception {
        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        mockMvc.perform(post("/_backend/workflows").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowKey\":\"stock_recommendation_research\",\"name\":\"Stock Recommendation Research\",\"engine\":\"langgraph\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowKey").value("stock_recommendation_research"));

        String layout = "{" +
                "\"name\":\"Stock Recommendation Research\"," +
                "\"engine\":\"langgraph\"," +
                "\"nodes\":[" +
                "{\"id\":\"start\",\"type\":\"workflowNode\",\"position\":{\"x\":80,\"y\":160},\"data\":{\"label\":\"Start\",\"nodeType\":\"start\",\"handler\":\"scheduler.manual\"}}," +
                "{\"id\":\"market\",\"type\":\"workflowNode\",\"position\":{\"x\":320,\"y\":160},\"data\":{\"label\":\"Market Analysis\",\"nodeType\":\"logic\",\"handler\":\"finance.market_analysis\",\"outputKeys\":[\"market_analysis\"]}}," +
                "{\"id\":\"financials\",\"type\":\"workflowNode\",\"position\":{\"x\":560,\"y\":160},\"data\":{\"label\":\"Financial Interpretation\",\"nodeType\":\"logic\",\"handler\":\"finance.financial_interpretation\",\"outputKeys\":[\"financial_interpretation\"]}}," +
                "{\"id\":\"recommend\",\"type\":\"workflowNode\",\"position\":{\"x\":800,\"y\":160},\"data\":{\"label\":\"Recommendation Aggregate\",\"nodeType\":\"agent\",\"handler\":\"finance.stock_recommendation_aggregate\",\"outputKeys\":[\"stock_recommendation\"]}}," +
                "{\"id\":\"end\",\"type\":\"workflowNode\",\"position\":{\"x\":1040,\"y\":160},\"data\":{\"label\":\"End\",\"nodeType\":\"end\",\"handler\":\"workflow.end\"}}" +
                "]," +
                "\"edges\":[" +
                "{\"id\":\"start-market\",\"source\":\"start\",\"target\":\"market\"}," +
                "{\"id\":\"market-financials\",\"source\":\"market\",\"target\":\"financials\"}," +
                "{\"id\":\"financials-recommend\",\"source\":\"financials\",\"target\":\"recommend\"}," +
                "{\"id\":\"recommend-end\",\"source\":\"recommend\",\"target\":\"end\"}" +
                "]}";

        mockMvc.perform(put("/_backend/workflows/stock_recommendation_research/layout").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(layout))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(5)))
                .andExpect(jsonPath("$.edges", hasSize(4)));

        String runJson = mockMvc.perform(post("/_backend/workflows/stock_recommendation_research/run").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"stock recommendation research\",\"inputs\":{\"ticker\":\"AAPL\",\"industry\":\"AI Infrastructure\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowKey").value("stock_recommendation_research"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andExpect(jsonPath("$.nodeCount").value(5))
                .andReturn().getResponse().getContentAsString();
        String runId = runJson.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");
        String traceId = runJson.replaceAll(".*\"traceId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/_backend/workflow/runs/" + runId + "/nodes").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[1].nodeId").value("market"))
                .andExpect(jsonPath("$[1].outputJson", notNullValue()));

        mockMvc.perform(get("/_backend/backtest/history").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.workflowRunId == '" + runId + "')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.workflowRunId == '" + runId + "')].traceId", hasItem(traceId)))
                .andExpect(jsonPath("$[?(@.workflowRunId == '" + runId + "')].subject", hasItem("stock recommendation research")))
                .andExpect(jsonPath("$[?(@.workflowRunId == '" + runId + "')].symbol", hasItem("AAPL")))
                .andExpect(jsonPath("$[?(@.workflowRunId == '" + runId + "')].nodeCount", hasItem(5)))
                .andExpect(jsonPath("$[?(@.workflowRunId == '" + runId + "')].resultJson", hasSize(1)));
    }

    @Test
    void stockAnalysisWorkflowExecutesAllNodesWithDefaultLayout() throws Exception {
        String login = mockMvc.perform(post("/_backend/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guanghui.nie\",\"password\":\"guanghui.nie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = login.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
        String auth = "Bearer " + token;

        // Verify stock_analysis layout returns the default parallel DAG
        mockMvc.perform(get("/_backend/workflows/stock_analysis/layout").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowKey").value("stock_analysis"))
                .andExpect(jsonPath("$.nodes", hasSize(9)))
                .andExpect(jsonPath("$.edges", hasSize(10)));

        // Run the workflow
        String runJson = mockMvc.perform(post("/_backend/workflows/stock_analysis/run").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"AAPL individual stock analysis\",\"inputs\":{\"ticker\":\"AAPL\",\"subject\":\"AAPL\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowKey").value("stock_analysis"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String runId = runJson.replaceAll(".*\"runId\":\"([^\"]+)\".*", "$1");

        // Verify all 9 nodes executed
        mockMvc.perform(get("/_backend/workflow/runs/" + runId + "/nodes").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(9)));
    }

    private Map<String, Object> marketOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("symbol", "AAPL");
        result.put("quote", marketQuote());
        result.put("financials", marketFinancials());
        result.put("news", marketNews());
        return result;
    }

    private Map<String, Object> marketQuote() {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("symbol", "AAPL");
        quote.put("provider", "yahoo-chart");
        quote.put("price", 205.35);
        quote.put("changePct", 2.67);
        quote.put("asOf", "2026-05-04T12:00:00Z");
        quote.put("asOfLocal", "2026-05-04T20:00:00+08:00");
        quote.put("timezone", "Asia/Hong_Kong");
        quote.put("delayHint", "Yahoo chart 1m data; availability may be exchange-delayed.");
        quote.put("sources", Arrays.asList(source("Yahoo Finance chart", "quote")));
        return quote;
    }

    private Map<String, Object> marketFinancials() {
        Map<String, Object> financials = new LinkedHashMap<>();
        financials.put("ok", true);
        financials.put("symbol", "AAPL");
        financials.put("provider", "sec-companyfacts");
        financials.put("asOf", "2025-10-31");
        financials.put("asOfLocal", "2025-10-31T00:00:00+08:00");
        financials.put("timezone", "Asia/Hong_Kong");
        financials.put("delayHint", "SEC CompanyFacts updates after issuer filing publication.");
        Map<String, Object> revenue = new LinkedHashMap<>();
        revenue.put("metric", "Revenues");
        revenue.put("label", "Revenue");
        revenue.put("value", 391035000000L);
        revenue.put("unit", "USD");
        financials.put("metrics", Arrays.asList(revenue));
        financials.put("sources", Arrays.asList(source("SEC CompanyFacts", "financials")));
        return financials;
    }

    private Map<String, Object> marketNews() {
        Map<String, Object> news = new LinkedHashMap<>();
        news.put("ok", true);
        news.put("symbol", "AAPL");
        news.put("provider", "yahoo-finance-rss");
        news.put("asOf", "2026-05-04T12:00:00Z");
        news.put("asOfLocal", "2026-05-04T20:00:00+08:00");
        news.put("timezone", "Asia/Hong_Kong");
        news.put("delayHint", "RSS reflects Yahoo Finance headline availability.");
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", "Apple updates guidance");
        article.put("url", "https://finance.yahoo.com/aapl-guidance");
        article.put("source", "Yahoo Finance");
        article.put("publishedAt", "2026-05-04T12:00:00Z");
        news.put("articles", Arrays.asList(article));
        news.put("sources", Arrays.asList(source("Yahoo Finance RSS", "news")));
        return news;
    }

    private Map<String, Object> source(String title, String type) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("title", title);
        source.put("url", "https://example.test/" + type);
        source.put("type", type);
        return source;
    }

    private Map<String, Object> usage(int prompt, int completion) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        usage.put("total_tokens", prompt + completion);
        return usage;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }
}
