package com.marketmind.alpha.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowNodeExecutionService {
    private final String executionToken;
    private final MarketDataService marketDataService;
    private final PortfolioService portfolioService;

    public WorkflowNodeExecutionService(@Value("${marketmind.dify.node-execution-token:}") String executionToken,
                                        MarketDataService marketDataService,
                                        PortfolioService portfolioService) {
        this.executionToken = executionToken;
        this.marketDataService = marketDataService;
        this.portfolioService = portfolioService;
    }

    public boolean authorized(String token) {
        return executionToken == null || executionToken.trim().isEmpty() || executionToken.equals(token);
    }

    public Map<String, Object> execute(Map<String, Object> request) {
        String functionName = stringValue(request.get("functionName"));
        String action = stringValue(request.get("action"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("functionName", functionName);
        if (!action.trim().isEmpty()) {
            result.put("action", action);
        }
        if (request.containsKey("params")) {
            result.put("params", request.get("params"));
        }
        if (request.containsKey("extra")) {
            result.put("extra", request.get("extra"));
        }
        result.put("executedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        if (isPortfolioHandler(functionName)) {
            return handlePortfolio(functionName, request, result);
        }
        if ("general.agent".equals(functionName) && "hydrate_market_data".equals(action)) {
            Map<String, Object> overview = marketDataService.overview(symbol(request));
            result.put("status", "ok");
            result.put("provider", "aegis-alpha-overview");
            result.put("overview", overview);
            result.put("quote", overview.get("quote"));
            result.put("financials", overview.get("financials"));
            result.put("news", overview.get("news"));
            return result;
        }
        if (isQuoteHandler(functionName)) {
            Map<String, Object> quote = marketDataService.quote(symbol(request));
            result.put("status", "ok");
            result.put("provider", quote.get("provider"));
            result.put("quote", quote);
            result.put("rows", Collections.singletonList(quote));
            result.put("sources", quote.get("sources"));
            return result;
        }
        if (isFinancialHandler(functionName)) {
            Map<String, Object> financials = marketDataService.financials(symbol(request));
            result.put("status", "ok");
            result.put("provider", financials.get("provider"));
            result.put("financials", financials);
            result.put("rows", listValue(financials.get("metrics")));
            result.put("sources", financials.get("sources"));
            return result;
        }
        if (isNewsHandler(functionName)) {
            Map<String, Object> news = marketDataService.news(symbol(request));
            result.put("status", "ok");
            result.put("provider", news.get("provider"));
            result.put("news", news);
            result.put("rows", listValue(news.get("articles")));
            result.put("sources", news.get("sources"));
            return result;
        }
        if ("agent.report".equals(functionName)) {
            result.put("report", "Dify report node executed. Connect a Dify LLM node for production report generation.");
            return result;
        }
        if ("notification.send".equals(functionName)) {
            result.put("notified", Boolean.TRUE);
            return result;
        }
        if ("workflow.run_exit".equals(functionName)) {
            result.put("exit_suggestions", new ArrayList<Object>());
            return result;
        }
        if (functionName != null && functionName.startsWith("fdb.")) {
            result.put("catalog", "FDB");
            result.put("status", "ok");
            result.put("rows", new ArrayList<Object>());
            return result;
        }
        result.put("status", "ok");
        return result;
    }

    private boolean isPortfolioHandler(String functionName) {
        return "portfolio.get_context".equals(functionName)
                || "portfolio.get_positions".equals(functionName)
                || "portfolio.positions".equals(functionName)
                || "portfolio.summary".equals(functionName)
                || "portfolio.trades".equals(functionName);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handlePortfolio(String functionName, Map<String, Object> request, Map<String, Object> result) {
        String portfolioId = resolvePortfolioId(request);
        result.put("status", "ok");
        result.put("provider", "aegis-alpha-portfolio");

        if ("portfolio.get_context".equals(functionName)) {
            if (!portfolioId.isEmpty()) {
                Map<String, Object> summary = portfolioService.summaryContract(portfolioId);
                result.put("portfolioId", portfolioId);
                result.put("summary", summary.get("summary"));
                result.put("dataCompleteness", summary.get("dataCompleteness"));
                result.put("sourceStatus", summary.get("sourceStatus"));
                result.put("positionCount", summary.get("positionCount"));
                result.put("tradeCount", summary.get("tradeCount"));
            } else {
                List<?> all = portfolioService.findAll();
                result.put("portfolioCount", all.size());
                result.put("portfolios", all);
            }
            return result;
        }

        if ("portfolio.get_positions".equals(functionName) || "portfolio.positions".equals(functionName)) {
            if (!portfolioId.isEmpty()) {
                Map<String, Object> contract = portfolioService.positionsContract(portfolioId);
                result.put("portfolioId", portfolioId);
                result.put("positions", contract.get("positions"));
                List<Object> tickers = new ArrayList<Object>();
                for (Map<String, Object> pos : (List<Map<String, Object>>) contract.get("positions")) {
                    tickers.add(pos.get("symbol"));
                }
                result.put("tickers", tickers);
                result.put("positionCount", contract.get("positionCount"));
            } else {
                result.put("positions", new ArrayList<Object>());
                result.put("tickers", new ArrayList<String>());
                result.put("positionCount", 0);
            }
            return result;
        }

        if ("portfolio.summary".equals(functionName)) {
            if (!portfolioId.isEmpty()) {
                Map<String, Object> contract = portfolioService.summaryContract(portfolioId);
                result.put("portfolioId", portfolioId);
                result.put("summary", contract.get("summary"));
                result.put("dataCompleteness", contract.get("dataCompleteness"));
            } else {
                List<?> all = portfolioService.findAll();
                result.put("portfolioCount", all.size());
                result.put("portfolios", all);
            }
            return result;
        }

        if ("portfolio.trades".equals(functionName)) {
            if (!portfolioId.isEmpty()) {
                Map<String, Object> contract = portfolioService.tradesContract(portfolioId);
                result.put("portfolioId", portfolioId);
                result.put("trades", contract.get("trades"));
                result.put("tradeCount", contract.get("tradeCount"));
            } else {
                result.put("trades", new ArrayList<Object>());
                result.put("tradeCount", 0);
            }
            return result;
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private String resolvePortfolioId(Map<String, Object> request) {
        String direct = firstText(request, "portfolioId", "portfolio_id");
        if (!direct.isEmpty()) { return direct; }
        Object params = request.get("params");
        if (params instanceof Map) {
            String nested = firstText((Map<String, Object>) params, "portfolioId", "portfolio_id");
            if (!nested.isEmpty()) { return nested; }
        }
        if (params instanceof String) {
            String nested = extractFromJsonLike((String) params, "portfolioId", "portfolio_id");
            if (!nested.isEmpty()) { return nested; }
        }
        Object extra = request.get("extra");
        if (extra instanceof Map) {
            String nested = firstText((Map<String, Object>) extra, "portfolioId", "portfolio_id");
            if (!nested.isEmpty()) { return nested; }
        }
        return "";
    }

    private String extractFromJsonLike(String text, String... keys) {
        for (String key : keys) {
            String needle = "\"" + key + "\"";
            int index = text.indexOf(needle);
            if (index < 0) { continue; }
            int colon = text.indexOf(":", index + needle.length());
            int quoteStart = colon < 0 ? -1 : text.indexOf("\"", colon);
            int quoteEnd = quoteStart < 0 ? -1 : text.indexOf("\"", quoteStart + 1);
            if (quoteEnd > quoteStart) { return text.substring(quoteStart + 1, quoteEnd); }
        }
        return "";
    }
    private boolean isQuoteHandler(String functionName) {
        return "fdb.daily_ohlc".equals(functionName)
                || "fdb.money_flow".equals(functionName)
                || "finance.market_analysis".equals(functionName)
                || "finance.technical_analysis".equals(functionName)
                || "finance.money_flow_analysis".equals(functionName)
                || "finance.peer_comparison".equals(functionName)
                || "finance.risk_reward_analysis".equals(functionName)
                || "finance.entry_strategy".equals(functionName);
    }

    private boolean isFinancialHandler(String functionName) {
        return "fdb.fundamental_data".equals(functionName)
                || "fdb.financial_ratios".equals(functionName)
                || "finance.financial_interpretation".equals(functionName)
                || "finance.fundamental_analysis".equals(functionName)
                || "finance.valuation_analysis".equals(functionName);
    }

    private boolean isNewsHandler(String functionName) {
        return "news.fetch_window".equals(functionName)
                || "fdb.global_news".equals(functionName)
                || "finance.industry_news".equals(functionName)
                || "finance.sentiment_monitor".equals(functionName)
                || "finance.tech_breakthrough".equals(functionName)
                || "finance.risk_assessment".equals(functionName)
                || "general.web_search".equals(functionName)
                || "general.fetch_news".equals(functionName)
                || "general.get_sector_news".equals(functionName)
                || "general.get_tech_breakthroughs".equals(functionName)
                || "finance.catalyst_analysis".equals(functionName)
                || "finance.thesis_builder".equals(functionName);
    }

    @SuppressWarnings("unchecked")
    private List<Object> listValue(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    private String symbol(Map<String, Object> request) {
        String direct = firstText(request, "symbol", "ticker", "code");
        if (!direct.isEmpty()) {
            return direct;
        }
        Object params = request.get("params");
        if (params instanceof Map) {
            String nested = firstText((Map<String, Object>) params, "symbol", "ticker", "code");
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        if (params instanceof String) {
            String nested = symbolFromJsonLikeString((String) params);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        Object extra = request.get("extra");
        if (extra instanceof Map) {
            String nested = firstText((Map<String, Object>) extra, "symbol", "ticker", "code");
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return "AAPL";
    }

    private String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                String value = stringValue(map.get(key)).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String symbolFromJsonLikeString(String text) {
        for (String key : new String[]{"symbol", "ticker", "code"}) {
            String needle = "\"" + key + "\"";
            int index = text.indexOf(needle);
            if (index < 0) {
                continue;
            }
            int colon = text.indexOf(':', index + needle.length());
            int quoteStart = colon < 0 ? -1 : text.indexOf('"', colon);
            int quoteEnd = quoteStart < 0 ? -1 : text.indexOf('"', quoteStart + 1);
            if (quoteEnd > quoteStart) {
                return text.substring(quoteStart + 1, quoteEnd);
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

