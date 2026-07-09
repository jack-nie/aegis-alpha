package com.aegis.alpha.service;

import com.aegis.alpha.domain.Portfolio;
import com.aegis.alpha.domain.PortfolioTrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.AbstractMap.SimpleEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkflowNodeExecutionServiceTest {
    private MarketDataService marketDataService;
    private PortfolioService portfolioService;
    private WorkflowNodeExecutionService service;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        portfolioService = mock(PortfolioService.class);
        service = new WorkflowNodeExecutionService("", marketDataService, portfolioService);
    }

    @SafeVarargs
    private static <K, V> Map<K, V> mapOf(Map.Entry<K, V>... entries) {
        Map<K, V> map = new HashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    private static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return new SimpleEntry<>(key, value);
    }

    @Test
    void authorizedWithEmptyConfigTokenIsRejected() {
        assertThat(service.authorized("")).isFalse();
        assertThat(service.authorized(null)).isFalse();
        assertThat(service.authorized("any")).isFalse();
    }

    @Test
    void authorizedWithMatchingToken() {
        WorkflowNodeExecutionService secured = new WorkflowNodeExecutionService("secret", marketDataService, portfolioService);
        assertThat(secured.authorized("secret")).isTrue();
        assertThat(secured.authorized("wrong")).isFalse();
        assertThat(secured.authorized(null)).isFalse();
        assertThat(secured.authorized("")).isFalse();
    }

    @Test
    void executeQuoteHandler() {
        Map<String, Object> quoteResult = new LinkedHashMap<>();
        quoteResult.put("provider", "yahoo-chart");
        quoteResult.put("symbol", "AAPL");
        quoteResult.put("sources", Collections.emptyList());
        when(marketDataService.quote("AAPL")).thenReturn(quoteResult);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "fdb.daily_ohlc");
        request.put("params", mapOf(entry("symbol", "AAPL")));

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("functionName")).isEqualTo("fdb.daily_ohlc");
        assertThat(result.get("quote")).isNotNull();
    }

    @Test
    void executeFinancialHandler() {
        Map<String, Object> finResult = new LinkedHashMap<>();
        finResult.put("provider", "sec-companyfacts");
        finResult.put("metrics", Collections.emptyList());
        finResult.put("sources", Collections.emptyList());
        when(marketDataService.financials("MSFT")).thenReturn(finResult);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "fdb.fundamental_data");
        request.put("symbol", "MSFT");

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("financials")).isNotNull();
    }

    @Test
    void executeNewsHandler() {
        Map<String, Object> newsResult = new LinkedHashMap<>();
        newsResult.put("provider", "yahoo-finance-rss");
        newsResult.put("articles", Collections.emptyList());
        newsResult.put("sources", Collections.emptyList());
        when(marketDataService.news("TSLA")).thenReturn(newsResult);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "news.fetch_window");
        request.put("params", mapOf(entry("ticker", "TSLA")));

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("news")).isNotNull();
    }

    @Test
    void executeHydrateMarketDataHandler() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("quote", mapOf(entry("symbol", "AAPL"), entry("price", 200)));
        overview.put("financials", mapOf(entry("symbol", "AAPL")));
        overview.put("news", mapOf(entry("symbol", "AAPL")));
        when(marketDataService.overview("NVDA")).thenReturn(overview);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "general.agent");
        request.put("action", "hydrate_market_data");
        request.put("symbol", "NVDA");

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("provider")).isEqualTo("aegis-alpha-overview");
    }

    @Test
    void executePortfolioGetContextWithPortfolioId() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("summary", mapOf(entry("nav", 250000)));
        contract.put("dataCompleteness", "DETAILS_SYNCED");
        contract.put("positionCount", 5);
        contract.put("tradeCount", 12);
        when(portfolioService.summaryContract("p-1")).thenReturn(contract);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "portfolio.get_context");
        request.put("portfolioId", "p-1");

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("portfolioId")).isEqualTo("p-1");
    }

    @Test
    void executePortfolioGetContextWithoutPortfolioId() {
        when(portfolioService.findAll()).thenReturn(Collections.emptyList());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "portfolio.get_context");

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("portfolioCount")).isEqualTo(0);
    }

    @Test
    void executeUnknownFunctionReturnsOk() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "unknown.handler");

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("functionName")).isEqualTo("unknown.handler");
    }

    @Test
    void executeAgentReportHandler() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "agent.report");

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("report")).isNotNull();
    }

    @Test
    void executeNotificationSendHandler() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "notification.send");

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("notified")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void executeFdbHandler() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "fdb.some_catalog");

        Map<String, Object> result = service.execute(request);

        assertThat(result.get("catalog")).isEqualTo("FDB");
        assertThat(result.get("status")).isEqualTo("ok");
    }

    @Test
    void symbolDefaultsToAAPL() {
        Map<String, Object> quoteResult = new LinkedHashMap<>();
        quoteResult.put("provider", "yahoo");
        quoteResult.put("sources", Collections.emptyList());
        when(marketDataService.quote("AAPL")).thenReturn(quoteResult);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "fdb.daily_ohlc");

        Map<String, Object> result = service.execute(request);

        verify(marketDataService).quote("AAPL");
    }

    @Test
    void symbolExtractedFromParams() {
        Map<String, Object> quoteResult = new LinkedHashMap<>();
        quoteResult.put("provider", "yahoo");
        quoteResult.put("sources", Collections.emptyList());
        when(marketDataService.quote("GOOGL")).thenReturn(quoteResult);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("functionName", "fdb.daily_ohlc");
        request.put("params", mapOf(entry("symbol", "GOOGL")));

        Map<String, Object> result = service.execute(request);

        verify(marketDataService).quote("GOOGL");
    }
}