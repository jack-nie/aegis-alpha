package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketDataService {
    private static final long DEFAULT_TTL_MS = 60000L;
    private static final String USER_AGENT = "Aegis Alpha contact: local@aegis-alpha.local";
    private static final String CONTRACT_TIMEZONE = "Asia/Hong_Kong";
    private static final List<String> FINANCIAL_METRICS = Arrays.asList(
            "Revenues",
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "SalesRevenueNet",
            "NetIncomeLoss",
            "OperatingIncomeLoss",
            "Assets",
            "Liabilities",
            "StockholdersEquity",
            "NetCashProvidedByUsedInOperatingActivities",
            "EarningsPerShareDiluted"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String yahooChartUrl;
    private final String stooqQuoteUrl;
    private final String secTickerMapUrl;
    private final String secCompanyFactsUrl;
    private final String yahooNewsRssUrl;
    private final String gdeltNewsUrl;
    private final String finnhubApiKey;
    private final String alphaVantageApiKey;
    private final String fmpApiKey;
    private final String twelveDataApiKey;
    private final String marketstackApiKey;
    private final String polygonApiKey;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<String, CacheEntry>();

    @Autowired
    public MarketDataService(ObjectMapper objectMapper,
                             @Value("${marketmind.market-data.yahoo-chart-url:https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1m&range=1d}") String yahooChartUrl,
                             @Value("${marketmind.market-data.stooq-quote-url:https://stooq.com/q/l/?s={symbol}.us&f=sd2t2ohlcv&h&e=csv}") String stooqQuoteUrl,
                             @Value("${marketmind.market-data.sec-ticker-map-url:https://www.sec.gov/files/company_tickers.json}") String secTickerMapUrl,
                             @Value("${marketmind.market-data.sec-companyfacts-url:https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json}") String secCompanyFactsUrl,
                             @Value("${marketmind.market-data.yahoo-news-rss-url:https://feeds.finance.yahoo.com/rss/2.0/headline?s={symbol}&region=US&lang=en-US}") String yahooNewsRssUrl,
                             @Value("${marketmind.market-data.gdelt-news-url:https://api.gdeltproject.org/api/v2/doc/doc?query={symbol}&mode=artlist&format=json&maxrecords=10}") String gdeltNewsUrl,
                             @Value("${FINNHUB_API_KEY:${MARKETMIND_FINNHUB_API_KEY:}}") String finnhubApiKey,
                             @Value("${ALPHA_VANTAGE_API_KEY:${MARKETMIND_ALPHA_VANTAGE_API_KEY:}}") String alphaVantageApiKey,
                             @Value("${FMP_API_KEY:${MARKETMIND_FMP_API_KEY:}}") String fmpApiKey,
                             @Value("${TWELVE_DATA_API_KEY:${MARKETMIND_TWELVE_DATA_API_KEY:}}") String twelveDataApiKey,
                             @Value("${MARKETSTACK_API_KEY:${MARKETMIND_MARKETSTACK_API_KEY:}}") String marketstackApiKey,
                             @Value("${POLYGON_API_KEY:${MARKETMIND_POLYGON_API_KEY:}}") String polygonApiKey,
                             @Value("${marketmind.market-data.http-timeout-ms:4000}") int httpTimeoutMs) {
        this(defaultRestTemplate(httpTimeoutMs), objectMapper, yahooChartUrl, stooqQuoteUrl, secTickerMapUrl, secCompanyFactsUrl,
                yahooNewsRssUrl, gdeltNewsUrl, finnhubApiKey, alphaVantageApiKey, fmpApiKey, twelveDataApiKey,
                marketstackApiKey, polygonApiKey);
    }

    static RestTemplate defaultRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    MarketDataService(RestTemplate restTemplate,
                      ObjectMapper objectMapper,
                      String yahooChartUrl,
                      String stooqQuoteUrl,
                      String secTickerMapUrl,
                      String secCompanyFactsUrl,
                      String yahooNewsRssUrl,
                      String gdeltNewsUrl,
                      String finnhubApiKey,
                      String alphaVantageApiKey,
                      String fmpApiKey,
                      String twelveDataApiKey,
                      String marketstackApiKey,
                      String polygonApiKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.yahooChartUrl = yahooChartUrl;
        this.stooqQuoteUrl = stooqQuoteUrl;
        this.secTickerMapUrl = secTickerMapUrl;
        this.secCompanyFactsUrl = secCompanyFactsUrl;
        this.yahooNewsRssUrl = yahooNewsRssUrl;
        this.gdeltNewsUrl = gdeltNewsUrl;
        this.finnhubApiKey = trim(finnhubApiKey);
        this.alphaVantageApiKey = trim(alphaVantageApiKey);
        this.fmpApiKey = trim(fmpApiKey);
        this.twelveDataApiKey = trim(twelveDataApiKey);
        this.marketstackApiKey = trim(marketstackApiKey);
        this.polygonApiKey = trim(polygonApiKey);
    }

    public Map<String, Object> quote(String symbol) {
        final String ticker = normalizeSymbol(symbol);
        return cached("quote:" + ticker, new SupplierMap() {
            @Override
            public Map<String, Object> get() {
                List<String> attempted = new ArrayList<String>();
                if (isAShare(ticker)) {
                    attempted.add("tencent-finance");
                    try {
                        return quoteFromTencent(ticker);
                    } catch (Exception ignored) {
                        // Continue to free providers.
                    }
                }
                if (!finnhubApiKey.isEmpty()) {
                    attempted.add("finnhub");
                    try {
                        return quoteFromFinnhub(ticker);
                    } catch (Exception ignored) {
                        // Continue to free providers.
                    }
                }
                if (!alphaVantageApiKey.isEmpty()) {
                    attempted.add("alpha-vantage");
                    try {
                        return quoteFromAlphaVantage(ticker);
                    } catch (Exception ignored) {
                        // Continue to next provider.
                    }
                }
                if (!fmpApiKey.isEmpty()) {
                    attempted.add("fmp");
                    try {
                        return quoteFromFmp(ticker);
                    } catch (Exception ignored) {
                        // Continue to free providers.
                    }
                }
                attempted.add("yahoo-chart");
                try {
                    return quoteFromYahoo(ticker);
                } catch (Exception ignored) {
                    attempted.add("stooq");
                    try {
                        return quoteFromStooq(ticker);
                    } catch (Exception ex) {
                        return unavailable("quote", ticker, attempted, ex);
                    }
                }
            }
        }, DEFAULT_TTL_MS);
    }

    public Map<String, Object> financials(String symbol) {
        final String ticker = normalizeSymbol(symbol);
        return cached("financials:" + ticker, new SupplierMap() {
            @Override
            public Map<String, Object> get() {
                List<String> attempted = new ArrayList<String>();
                if (isAShare(ticker)) {
                    attempted.add("tencent-finance");
                    try {
                        return financialsFromTencent(ticker);
                    } catch (Exception ignored) {
                        // Continue to free providers.
                    }
                }
                attempted.add("sec-companyfacts");
                try {
                    return financialsFromSec(ticker);
                } catch (Exception secError) {
                    if (!fmpApiKey.isEmpty()) {
                        attempted.add("fmp-income-statement");
                        try {
                            return financialsFromFmp(ticker);
                        } catch (Exception ignored) {
                            // Continue to Alpha Vantage fallback.
                        }
                    }
                    if (!alphaVantageApiKey.isEmpty()) {
                        attempted.add("alpha-vantage-overview");
                        try {
                            return financialsFromAlphaOverview(ticker);
                        } catch (Exception ignored) {
                            // Report the original SEC failure if every fallback fails.
                        }
                    }
                    return unavailable("financials", ticker, attempted, secError);
                }
            }
        }, 300000L);
    }

    public Map<String, Object> news(String symbol) {
        final String ticker = normalizeSymbol(symbol);
        return cached("news:" + ticker, new SupplierMap() {
            @Override
            public Map<String, Object> get() {
                List<String> attempted = new ArrayList<String>();
                if (isAShare(ticker)) {
                    attempted.add("eastmoney");
                    try {
                        return newsFromEastMoney(ticker);
                    } catch (Exception ignored) {
                        // Continue to free providers.
                    }
                }
                if (!finnhubApiKey.isEmpty()) {
                    attempted.add("finnhub-news");
                    try {
                        return newsFromFinnhub(ticker);
                    } catch (Exception ignored) {
                        // Continue to free providers.
                    }
                }
                if (!alphaVantageApiKey.isEmpty()) {
                    attempted.add("alpha-vantage-news");
                    try {
                        return newsFromAlphaVantage(ticker);
                    } catch (Exception ignored) {
                        // Continue to next provider.
                    }
                }
                if (!fmpApiKey.isEmpty()) {
                    attempted.add("fmp-news");
                    try {
                        return newsFromFmp(ticker);
                    } catch (Exception ignored) {
                        // Continue to free providers.
                    }
                }
                attempted.add("yahoo-finance-rss");
                try {
                    return newsFromYahooRss(ticker);
                } catch (Exception ignored) {
                    attempted.add("gdelt");
                    try {
                        return newsFromGdelt(ticker);
                    } catch (Exception ex) {
                        return unavailable("news", ticker, attempted, ex);
                    }
                }
            }
        }, 120000L);
    }

    public Map<String, Object> overview(String symbol) {
        Map<String, Object> result = base("market-data-overview", normalizeSymbol(symbol), "aegis-alpha");
        result.put("quote", quote(symbol));
        result.put("financials", financials(symbol));
        result.put("news", news(symbol));
        result.put("configuredProviders", configuredProviders());
        return result;
    }

    private Map<String, Object> quoteFromYahoo(String ticker) throws Exception {
        String url = expand(yahooChartUrl, ticker);
        JsonNode root = objectMapper.readTree(get(url));
        JsonNode result = root.path("chart").path("result").path(0);
        JsonNode meta = result.path("meta");
        double price = number(meta.path("regularMarketPrice"));
        double previous = number(meta.path("previousClose"));
        double change = round(price - previous, 4);
        double changePct = previous == 0 ? 0 : round(change / previous * 100, 4);
        Map<String, Object> quote = base("quote", ticker, "yahoo-chart");
        quote.put("name", text(meta.path("longName"), ticker));
        quote.put("price", price);
        quote.put("previousClose", previous);
        quote.put("change", change);
        quote.put("changePct", changePct);
        quote.put("currency", text(meta.path("currency"), "USD"));
        quote.put("exchange", text(meta.path("exchangeName"), ""));
        quote.put("volume", latestNumber(result.path("indicators").path("quote").path(0).path("volume")));
        quote.put("asOf", epoch(meta.path("regularMarketTime")));
        quote.put("isRealtime", Boolean.TRUE);
        quote.put("delayHint", "Yahoo chart 1m data; availability may be exchange-delayed.");
        quote.put("sources", Collections.singletonList(source("Yahoo Finance chart", url, "quote")));
        return quote;
    }

    private Map<String, Object> quoteFromStooq(String ticker) throws Exception {
        String url = expand(stooqQuoteUrl, ticker.toLowerCase());
        String csv = get(url);
        String[] lines = csv.trim().split("\\r?\\n");
        if (lines.length < 2) {
            throw new IllegalStateException("Stooq returned no quote rows");
        }
        String[] values = lines[1].split(",");
        if (values.length < 8 || "N/D".equalsIgnoreCase(values[6])) {
            throw new IllegalStateException("Stooq quote row is incomplete");
        }
        double open = parseDouble(values[3]);
        double close = parseDouble(values[6]);
        double change = round(close - open, 4);
        double changePct = open == 0 ? 0 : round(change / open * 100, 4);
        Map<String, Object> quote = base("quote", ticker, "stooq");
        quote.put("name", ticker);
        quote.put("price", close);
        quote.put("previousClose", open);
        quote.put("change", change);
        quote.put("changePct", changePct);
        quote.put("currency", "USD");
        quote.put("exchange", "US");
        quote.put("volume", parseLong(values[7]));
        quote.put("asOf", values[1] + "T" + values[2] + "Z");
        quote.put("isRealtime", Boolean.FALSE);
        quote.put("delayHint", "Stooq free quote data may be delayed.");
        quote.put("sources", Collections.singletonList(source("Stooq quote CSV", url, "quote")));
        return quote;
    }

    private Map<String, Object> quoteFromFinnhub(String ticker) throws Exception {
        String url = "https://finnhub.io/api/v1/quote?symbol=" + ticker + "&token=" + finnhubApiKey;
        JsonNode root = objectMapper.readTree(get(url));
        double price = number(root.path("c"));
        double previous = number(root.path("pc"));
        Map<String, Object> quote = base("quote", ticker, "finnhub");
        quote.put("name", ticker);
        quote.put("price", price);
        quote.put("previousClose", previous);
        quote.put("change", round(number(root.path("d")), 4));
        quote.put("changePct", round(number(root.path("dp")), 4));
        quote.put("currency", "USD");
        quote.put("exchange", "");
        quote.put("volume", null);
        quote.put("asOf", epoch(root.path("t")));
        quote.put("isRealtime", Boolean.TRUE);
        quote.put("delayHint", "Finnhub entitlement controls realtime availability.");
        quote.put("sources", Collections.singletonList(source("Finnhub quote", "https://finnhub.io/docs/api/quote", "quote")));
        return quote;
    }

    private Map<String, Object> quoteFromAlphaVantage(String ticker) throws Exception {
        String url = "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=" + ticker + "&apikey=" + alphaVantageApiKey;
        JsonNode quoteNode = objectMapper.readTree(get(url)).path("Global Quote");
        if (quoteNode.isMissingNode() || quoteNode.size() == 0) {
            throw new IllegalStateException("Alpha Vantage returned no global quote");
        }
        double price = parseDouble(text(quoteNode.path("05. price"), "0"));
        double previous = parseDouble(text(quoteNode.path("08. previous close"), "0"));
        double change = parseDouble(text(quoteNode.path("09. change"), "0"));
        double changePct = parsePercent(text(quoteNode.path("10. change percent"), "0"));
        Map<String, Object> quote = base("quote", ticker, "alpha-vantage");
        quote.put("name", ticker);
        quote.put("price", price);
        quote.put("previousClose", previous);
        quote.put("change", change);
        quote.put("changePct", changePct);
        quote.put("currency", "USD");
        quote.put("exchange", "");
        quote.put("volume", null);
        quote.put("asOf", nowIso());
        quote.put("isRealtime", Boolean.TRUE);
        quote.put("delayHint", "Alpha Vantage quote latency depends on subscription entitlement.");
        quote.put("sources", Collections.singletonList(source("Alpha Vantage Global Quote", "https://www.alphavantage.co/documentation/#latestprice", "quote")));
        return quote;
    }

    private Map<String, Object> quoteFromFmp(String ticker) throws Exception {
        String url = "https://financialmodelingprep.com/api/v3/quote/" + ticker + "?apikey=" + fmpApiKey;
        JsonNode item = objectMapper.readTree(get(url)).path(0);
        if (item.isMissingNode()) {
            throw new IllegalStateException("FMP returned no quote rows");
        }
        Map<String, Object> quote = base("quote", ticker, "fmp-quote");
        quote.put("name", text(item.path("name"), ticker));
        quote.put("price", number(item.path("price")));
        quote.put("previousClose", number(item.path("previousClose")));
        quote.put("change", round(number(item.path("change")), 4));
        quote.put("changePct", round(number(item.path("changesPercentage")), 4));
        quote.put("currency", "USD");
        quote.put("exchange", text(item.path("exchange"), ""));
        quote.put("volume", item.path("volume").isNumber() ? item.path("volume").numberValue() : null);
        quote.put("asOf", epoch(item.path("timestamp")));
        quote.put("isRealtime", Boolean.TRUE);
        quote.put("delayHint", "FMP quote latency depends on subscription entitlement.");
        quote.put("sources", Collections.singletonList(source("FMP quote", "https://financialmodelingprep.com/developer/docs/stock-market-quote-free-api", "quote")));
        return quote;
    }

    private Map<String, Object> financialsFromSec(String ticker) throws Exception {
        String cik = resolveCik(ticker);
        String url = secCompanyFactsUrl.replace("{cik}", cik);
        JsonNode root = objectMapper.readTree(get(url));
        Map<String, Object> payload = base("financials", ticker, "sec-companyfacts");
        payload.put("companyName", text(root.path("entityName"), ticker));
        payload.put("cik", cik);
        payload.put("sources", Collections.singletonList(source("SEC CompanyFacts", url, "financials")));
        List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
        String asOf = "";
        JsonNode gaap = root.path("facts").path("us-gaap");
        for (String metric : FINANCIAL_METRICS) {
            JsonNode node = gaap.path(metric);
            if (node.isMissingNode()) {
                continue;
            }
            MetricFact latest = latestMetric(metric, node);
            if (latest != null) {
                metrics.add(latest.values);
                String filed = string(latest.values.get("filed"));
                if (filed.compareTo(asOf) > 0) {
                    asOf = filed;
                }
            }
        }
        payload.put("metrics", metrics);
        payload.put("asOf", asOf.isEmpty() ? nowIso() : asOf);
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "SEC CompanyFacts updates after issuer filing publication.");
        return payload;
    }

    private Map<String, Object> financialsFromFmp(String ticker) throws Exception {
        String url = "https://financialmodelingprep.com/api/v3/income-statement/" + ticker + "?period=annual&limit=1&apikey=" + fmpApiKey;
        JsonNode item = objectMapper.readTree(get(url)).path(0);
        if (item.isMissingNode()) {
            throw new IllegalStateException("FMP returned no income statement rows");
        }
        Map<String, Object> payload = base("financials", ticker, "fmp-income-statement");
        payload.put("companyName", ticker);
        payload.put("asOf", text(item.path("fillingDate"), text(item.path("date"), nowIso())));
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "FMP financial statement availability depends on vendor ingestion and plan.");
        payload.put("sources", Collections.singletonList(source("FMP income statement", "https://financialmodelingprep.com/developer/docs/financial-statement-free-api", "financials")));
        List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
        addFmpMetric(metrics, item, "revenue", "USD");
        addFmpMetric(metrics, item, "netIncome", "USD");
        addFmpMetric(metrics, item, "operatingIncome", "USD");
        addFmpMetric(metrics, item, "epsdiluted", "USD/share");
        payload.put("metrics", metrics);
        return payload;
    }

    private Map<String, Object> financialsFromAlphaOverview(String ticker) throws Exception {
        String url = "https://www.alphavantage.co/query?function=OVERVIEW&symbol=" + ticker + "&apikey=" + alphaVantageApiKey;
        JsonNode root = objectMapper.readTree(get(url));
        if (!root.has("Symbol")) {
            throw new IllegalStateException("Alpha Vantage returned no overview");
        }
        Map<String, Object> payload = base("financials", ticker, "alpha-vantage-overview");
        payload.put("companyName", text(root.path("Name"), ticker));
        payload.put("asOf", text(root.path("LatestQuarter"), nowIso()));
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "Alpha Vantage overview is a derived fundamental snapshot.");
        payload.put("sources", Collections.singletonList(source("Alpha Vantage company overview", "https://www.alphavantage.co/documentation/#company-overview", "financials")));
        List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
        addAlphaMetric(metrics, root, "RevenueTTM", "USD");
        addAlphaMetric(metrics, root, "GrossProfitTTM", "USD");
        addAlphaMetric(metrics, root, "EBITDA", "USD");
        addAlphaMetric(metrics, root, "EPS", "USD/share");
        addAlphaMetric(metrics, root, "PERatio", "ratio");
        payload.put("metrics", metrics);
        return payload;
    }

    private void addFmpMetric(List<Map<String, Object>> metrics, JsonNode item, String key, String unit) {
        if (!item.path(key).isNumber()) {
            return;
        }
        Map<String, Object> metric = new LinkedHashMap<String, Object>();
        metric.put("metric", key);
        metric.put("label", metricLabel(key));
        metric.put("value", item.path(key).numberValue());
        metric.put("unit", unit);
        metric.put("filed", text(item.path("fillingDate"), ""));
        metric.put("end", text(item.path("date"), ""));
        metric.put("form", "income-statement");
        metrics.add(metric);
    }

    private void addAlphaMetric(List<Map<String, Object>> metrics, JsonNode item, String key, String unit) {
        String raw = text(item.path(key), "");
        if (raw.isEmpty() || "None".equalsIgnoreCase(raw)) {
            return;
        }
        Map<String, Object> metric = new LinkedHashMap<String, Object>();
        metric.put("metric", key);
        metric.put("label", metricLabel(key));
        metric.put("value", parseDouble(raw));
        metric.put("unit", unit);
        metric.put("filed", text(item.path("LatestQuarter"), ""));
        metric.put("end", text(item.path("LatestQuarter"), ""));
        metric.put("form", "company-overview");
        metrics.add(metric);
    }

    private String resolveCik(String ticker) throws Exception {
        JsonNode root = objectMapper.readTree(get(secTickerMapUrl));
        for (JsonNode item : root) {
            if (ticker.equalsIgnoreCase(text(item.path("ticker"), ""))) {
                int cik = item.path("cik_str").asInt();
                return String.format("%010d", cik);
            }
        }
        throw new IllegalArgumentException("No SEC CIK mapping for " + ticker);
    }

    private MetricFact latestMetric(String metric, JsonNode metricNode) {
        JsonNode units = metricNode.path("units");
        List<MetricFact> facts = new ArrayList<MetricFact>();
        units.fields().forEachRemaining(entry -> {
            String unit = entry.getKey();
            for (JsonNode fact : entry.getValue()) {
                if (!fact.has("val")) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("metric", metric);
                row.put("label", metricLabel(metric));
                row.put("description", text(metricNode.path("description"), ""));
                row.put("value", fact.path("val").isNumber() ? fact.path("val").numberValue() : fact.path("val").asText());
                row.put("unit", unit);
                row.put("fy", fact.path("fy").isMissingNode() ? null : fact.path("fy").asInt());
                row.put("fp", text(fact.path("fp"), ""));
                row.put("form", text(fact.path("form"), ""));
                row.put("filed", text(fact.path("filed"), ""));
                row.put("end", text(fact.path("end"), ""));
                facts.add(new MetricFact(row));
            }
        });
        if (facts.isEmpty()) {
            return null;
        }
        facts.sort(Comparator.comparing((MetricFact fact) -> string(fact.values.get("filed")))
                .thenComparing(fact -> string(fact.values.get("end"))));
        return facts.get(facts.size() - 1);
    }

    private Map<String, Object> newsFromYahooRss(String ticker) throws Exception {
        String url = expand(yahooNewsRssUrl, ticker);
        byte[] xml = getBytes(url);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList items = document.getElementsByTagName("item");
        List<Map<String, Object>> articles = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < Math.min(items.getLength(), 10); i++) {
            Element item = (Element) items.item(i);
            Map<String, Object> article = new LinkedHashMap<String, Object>();
            article.put("title", childText(item, "title"));
            article.put("url", childText(item, "link"));
            article.put("publishedAt", normalizeRssDate(childText(item, "pubDate")));
            article.put("source", childText(item, "source").isEmpty() ? "Yahoo Finance" : childText(item, "source"));
            article.put("provider", "yahoo-finance-rss");
            articles.add(article);
        }
        Map<String, Object> payload = base("news", ticker, "yahoo-finance-rss");
        payload.put("articles", articles);
        payload.put("asOf", articles.isEmpty() ? nowIso() : string(articles.get(0).get("publishedAt")));
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "RSS reflects Yahoo Finance headline availability.");
        payload.put("sources", Collections.singletonList(source("Yahoo Finance RSS", url, "news")));
        return payload;
    }

    private Map<String, Object> newsFromGdelt(String ticker) throws Exception {
        String url = expand(gdeltNewsUrl, ticker);
        JsonNode root = objectMapper.readTree(get(url));
        List<Map<String, Object>> articles = new ArrayList<Map<String, Object>>();
        for (JsonNode item : root.path("articles")) {
            Map<String, Object> article = new LinkedHashMap<String, Object>();
            article.put("title", text(item.path("title"), ""));
            article.put("url", text(item.path("url"), ""));
            article.put("publishedAt", text(item.path("seendate"), ""));
            article.put("source", text(item.path("sourceCountry"), "GDELT"));
            article.put("provider", "gdelt");
            articles.add(article);
        }
        Map<String, Object> payload = base("news", ticker, "gdelt");
        payload.put("articles", articles);
        payload.put("asOf", articles.isEmpty() ? nowIso() : string(articles.get(0).get("publishedAt")));
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "GDELT is a broad news index, not a licensed market news feed.");
        payload.put("sources", Collections.singletonList(source("GDELT DOC 2.0", url, "news")));
        return payload;
    }

    private Map<String, Object> newsFromAlphaVantage(String ticker) throws Exception {
        String url = "https://www.alphavantage.co/query?function=NEWS_SENTIMENT&tickers=" + ticker + "&limit=10&apikey=" + alphaVantageApiKey;
        JsonNode root = objectMapper.readTree(get(url));
        List<Map<String, Object>> articles = new ArrayList<Map<String, Object>>();
        for (JsonNode item : root.path("feed")) {
            Map<String, Object> article = new LinkedHashMap<String, Object>();
            article.put("title", text(item.path("title"), ""));
            article.put("url", text(item.path("url"), ""));
            article.put("publishedAt", text(item.path("time_published"), ""));
            article.put("source", text(item.path("source"), "Alpha Vantage"));
            article.put("provider", "alpha-vantage-news");
            articles.add(article);
            if (articles.size() >= 10) {
                break;
            }
        }
        Map<String, Object> payload = base("news", ticker, "alpha-vantage-news");
        payload.put("articles", articles);
        payload.put("asOf", articles.isEmpty() ? nowIso() : string(articles.get(0).get("publishedAt")));
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "Alpha Vantage news availability depends on API plan and vendor ingestion.");
        payload.put("sources", Collections.singletonList(source("Alpha Vantage news sentiment", "https://www.alphavantage.co/documentation/#news-sentiment", "news")));
        return payload;
    }

    private Map<String, Object> newsFromFmp(String ticker) throws Exception {
        String url = "https://financialmodelingprep.com/api/v3/stock_news?tickers=" + ticker + "&limit=10&apikey=" + fmpApiKey;
        JsonNode root = objectMapper.readTree(get(url));
        List<Map<String, Object>> articles = new ArrayList<Map<String, Object>>();
        for (JsonNode item : root) {
            Map<String, Object> article = new LinkedHashMap<String, Object>();
            article.put("title", text(item.path("title"), ""));
            article.put("url", text(item.path("url"), ""));
            article.put("publishedAt", text(item.path("publishedDate"), ""));
            article.put("source", text(item.path("site"), "FMP"));
            article.put("provider", "fmp-news");
            articles.add(article);
            if (articles.size() >= 10) {
                break;
            }
        }
        Map<String, Object> payload = base("news", ticker, "fmp-news");
        payload.put("articles", articles);
        payload.put("asOf", articles.isEmpty() ? nowIso() : string(articles.get(0).get("publishedAt")));
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "FMP news availability depends on API plan and vendor ingestion.");
        payload.put("sources", Collections.singletonList(source("FMP stock news", "https://financialmodelingprep.com/developer/docs/stock-news-api", "news")));
        return payload;
    }

    private Map<String, Object> newsFromFinnhub(String ticker) throws Exception {
        String today = DateTimeFormatter.ISO_LOCAL_DATE.format(ZonedDateTime.now());
        String url = "https://finnhub.io/api/v1/company-news?symbol=" + ticker + "&from=" + today + "&to=" + today + "&token=" + finnhubApiKey;
        JsonNode root = objectMapper.readTree(get(url));
        List<Map<String, Object>> articles = new ArrayList<Map<String, Object>>();
        for (JsonNode item : root) {
            Map<String, Object> article = new LinkedHashMap<String, Object>();
            article.put("title", text(item.path("headline"), ""));
            article.put("url", text(item.path("url"), ""));
            article.put("publishedAt", epoch(item.path("datetime")));
            article.put("source", text(item.path("source"), "Finnhub"));
            article.put("provider", "finnhub-news");
            articles.add(article);
            if (articles.size() >= 10) {
                break;
            }
        }
        Map<String, Object> payload = base("news", ticker, "finnhub-news");
        payload.put("articles", articles);
        payload.put("asOf", articles.isEmpty() ? nowIso() : string(articles.get(0).get("publishedAt")));
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "Finnhub news availability depends on subscription entitlements.");
        payload.put("sources", Collections.singletonList(source("Finnhub company news", "https://finnhub.io/docs/api/company-news", "news")));
        return payload;
    }

    private Map<String, Object> unavailable(String kind, String ticker, List<String> attempted, Exception ex) {
        Map<String, Object> payload = base(kind, ticker, "unavailable");
        payload.put("status", "unavailable");
        payload.put("ok", Boolean.FALSE);
        payload.put("error", ex.getMessage());
        payload.put("attemptedProviders", attempted);
        payload.put("isRealtime", Boolean.FALSE);
        payload.put("delayHint", "No provider returned usable data.");
        if ("news".equals(kind)) {
            payload.put("articles", new ArrayList<Object>());
        } else if ("financials".equals(kind)) {
            payload.put("metrics", new ArrayList<Object>());
        }
        return payload;
    }

    private Map<String, Object> configuredProviders() {
        Map<String, Object> providers = new LinkedHashMap<String, Object>();
        providers.put("free", Arrays.asList("SEC EDGAR CompanyFacts", "Yahoo Finance chart", "Yahoo Finance RSS", "Stooq", "GDELT"));
        Map<String, Object> keyed = new LinkedHashMap<String, Object>();
        keyed.put("finnhub", !finnhubApiKey.isEmpty());
        keyed.put("alphaVantage", !alphaVantageApiKey.isEmpty());
        keyed.put("financialModelingPrep", !fmpApiKey.isEmpty());
        keyed.put("twelveData", !twelveDataApiKey.isEmpty());
        keyed.put("marketstack", !marketstackApiKey.isEmpty());
        keyed.put("polygon", !polygonApiKey.isEmpty());
        providers.put("apiKeyBackups", keyed);
        return providers;
    }

    private Map<String, Object> base(String kind, String ticker, String provider) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("ok", Boolean.TRUE);
        payload.put("kind", kind);
        payload.put("symbol", ticker);
        payload.put("provider", provider);
        payload.put("retrievedAt", nowIso());
        return payload;
    }

    private Map<String, Object> source(String title, String url, String type) {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("title", title);
        source.put("url", url);
        source.put("type", type);
        return source;
    }

    private Map<String, Object> cached(String key, SupplierMap supplier, long ttlMs) {
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expiresAt > now) {
            return new LinkedHashMap<String, Object>(entry.value);
        }
        Map<String, Object> value = enrichContract(supplier.get());
        cache.put(key, new CacheEntry(new LinkedHashMap<String, Object>(value), now + ttlMs));
        return value;
    }

    private String get(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        ResponseEntity<String> response = restTemplate.exchange(java.net.URI.create(url), HttpMethod.GET, new HttpEntity<String>(headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("HTTP request failed for " + url);
        }
        return response.getBody();
    }

    private byte[] getBytes(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        ResponseEntity<byte[]> response = restTemplate.exchange(java.net.URI.create(url), HttpMethod.GET, new HttpEntity<String>(headers), byte[].class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("HTTP request failed for " + url);
        }
        return response.getBody();
    }

    private String expand(String pattern, String ticker) {
        return pattern.replace("{symbol}", ticker);
    }

    private String normalizeSymbol(String symbol) {
        String raw = symbol == null ? "" : symbol.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String resolved = resolveAShareSymbol(raw);
        String ticker = resolved.toUpperCase();
        return ticker.replaceAll("[^A-Z0-9._-]", "");
    }

    private static final java.util.Map<String, String> CHINESE_STOCK_NAMES = new java.util.LinkedHashMap<String, String>();
    static {
        CHINESE_STOCK_NAMES.put("沪电股份", "002463.SZ");
        CHINESE_STOCK_NAMES.put("贵州茅台", "600519.SH");
        CHINESE_STOCK_NAMES.put("中国平安", "601318.SH");
        CHINESE_STOCK_NAMES.put("招商银行", "600036.SH");
        CHINESE_STOCK_NAMES.put("宁德时代", "300750.SZ");
        CHINESE_STOCK_NAMES.put("比亚迪", "002594.SZ");
        CHINESE_STOCK_NAMES.put("隆基绿能", "601012.SH");
        CHINESE_STOCK_NAMES.put("五粮液", "000858.SZ");
        CHINESE_STOCK_NAMES.put("美的集团", "000333.SZ");
        CHINESE_STOCK_NAMES.put("中信证券", "600030.SH");
        CHINESE_STOCK_NAMES.put("立讯精密", "002475.SZ");
        CHINESE_STOCK_NAMES.put("海康威视", "002415.SZ");
        CHINESE_STOCK_NAMES.put("恒瑞医药", "600276.SH");
        CHINESE_STOCK_NAMES.put("迈瑞医疗", "300760.SZ");
        CHINESE_STOCK_NAMES.put("药明康德", "603259.SH");
        CHINESE_STOCK_NAMES.put("东方财富", "300059.SZ");
        CHINESE_STOCK_NAMES.put("三一重工", "600031.SH");
        CHINESE_STOCK_NAMES.put("紫金矿业", "601899.SH");
        CHINESE_STOCK_NAMES.put("中国中免", "601888.SH");
        CHINESE_STOCK_NAMES.put("片仔癌", "600436.SH");
    }

    public String resolveAShareSymbolPublic(String input) {
        return resolveAShareSymbol(input);
    }

    private String resolveAShareSymbol(String input) {
        if (input == null) return "";
        String trimmed = input.trim();
        if (CHINESE_STOCK_NAMES.containsKey(trimmed)) {
            return CHINESE_STOCK_NAMES.get(trimmed);
        }
        for (java.util.Map.Entry<String, String> entry : CHINESE_STOCK_NAMES.entrySet()) {
            if (trimmed.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return trimmed;
    }

    private boolean isAShare(String ticker) {
        if (ticker == null) return false;
        String t = ticker.toUpperCase();
        if (t.endsWith(".SZ") || t.endsWith(".SH")) return true;
        if (t.matches("\\d{6}")) return true;
        return false;
    }

    private String toTencentCode(String ticker) {
        String t = ticker.toUpperCase();
        if (t.endsWith(".SZ")) return "sz" + t.substring(0, 6);
        if (t.endsWith(".SH")) return "sh" + t.substring(0, 6);
        if (t.matches("\\d{6}")) {
            String code = t.substring(0, 6);
            if (code.startsWith("6")) return "sh" + code;
            return "sz" + code;
        }
        return t.toLowerCase();
    }

    private String getGBK(String url) {
        byte[] raw = getBytes(url);
        try {
            return new String(raw, "GBK");
        } catch (Exception ex) {
            return new String(raw);
        }
    }

    private Map<String, Object> quoteFromTencent(String ticker) throws Exception {
        String code = toTencentCode(ticker);
        String url = "http://qt.gtimg.cn/q=" + code;
        String raw = getGBK(url);
        if (raw == null || raw.trim().isEmpty() || raw.contains("pv_none")) {
            throw new IllegalStateException("Tencent Finance returned no data for " + ticker);
        }
        String data = raw;
        int start = data.indexOf('"');
        int end = data.lastIndexOf('"');
        if (start >= 0 && end > start) {
            data = data.substring(start + 1, end);
        }
        String[] parts = data.split("~");
        if (parts.length < 45) {
            throw new IllegalStateException("Tencent Finance response too short for " + ticker);
        }
        double price = parseDouble(parts[3]);
        double previous = parseDouble(parts[4]);
        double open = parseDouble(parts[5]);
        Long volumeObj = parseLong(parts[6]);
        long volume = volumeObj != null ? volumeObj : 0L;
        double change = round(price - previous, 4);
        double changePct = previous == 0 ? 0 : round(change / previous * 100, 4);

        Map<String, Object> quote = base("quote", ticker, "tencent-finance");
        quote.put("name", parts[1]);
        quote.put("price", price);
        quote.put("open", open);
        quote.put("previousClose", previous);
        quote.put("high", parseDouble(parts[33]));
        quote.put("low", parseDouble(parts[34]));
        quote.put("volume", volume);
        quote.put("change", change);
        quote.put("changePct", changePct);
        quote.put("marketCap", parts[44]);
        quote.put("asOf", parts[30]);
        quote.put("isRealtime", Boolean.TRUE);
        quote.put("delayHint", "Tencent Finance real-time quote data.");
        quote.put("sources", Collections.singletonList(source("Tencent Finance", url, "quote")));
        return quote;
    }
    private Map<String, Object> newsFromEastMoney(String ticker) throws Exception {
        String code = ticker.replaceAll("[^0-9]", "");
        String url = "https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2516&k=" + code + "&num=8&page=1";
        byte[] rawBytes = getBytes(url);
        String raw = new String(rawBytes, "UTF-8");
        List<Map<String, Object>> articles = new ArrayList<Map<String, Object>>();
        if (raw != null && !raw.isEmpty()) {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode dataNode = root.path("result").path("data");
            if (dataNode.isArray()) {
                for (JsonNode item : dataNode) {
                    String title = text(item.path("title"), "");
                    if (title.isEmpty()) continue;
                    Map<String, Object> article = new LinkedHashMap<String, Object>();
                    article.put("title", title);
                    article.put("url", text(item.path("url"), ""));
                    String ctime = text(item.path("ctime"), "");
                    if (!ctime.isEmpty()) {
                        try {
                            article.put("publishedAt", java.time.Instant.ofEpochSecond(Long.parseLong(ctime)).toString());
                        } catch (Exception ex) {
                            article.put("publishedAt", ctime);
                        }
                    } else {
                        article.put("publishedAt", "");
                    }
                    article.put("source", text(item.path("media_name"), "Sina Finance"));
                    article.put("provider", "sina-finance");
                    articles.add(article);
                }
            }
        }
        Map<String, Object> payload = base("news", ticker, "sina-finance");
        payload.put("articles", articles);
        payload.put("asOf", articles.isEmpty() ? nowIso() : string(articles.get(0).get("publishedAt")));
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "Sina Finance news feed.");
        payload.put("sources", Collections.singletonList(source("Sina Finance News", url, "news")));
        return payload;
    }

    private Map<String, Object> financialsFromTencent(String ticker) throws Exception {
        String code = toTencentCode(ticker);
        String url = "http://qt.gtimg.cn/q=" + code;
        String raw = getGBK(url);
        if (raw == null || raw.trim().isEmpty() || raw.contains("pv_none")) {
            throw new IllegalStateException("Tencent Finance returned no financials for " + ticker);
        }
        String data = raw;
        int start = data.indexOf('"');
        int end = data.lastIndexOf('"');
        if (start >= 0 && end > start) {
            data = data.substring(start + 1, end);
        }
        String[] parts = data.split("~");
        if (parts.length < 45) {
            throw new IllegalStateException("Tencent Finance response too short for " + ticker);
        }
        Map<String, Object> payload = base("financials", ticker, "tencent-finance");
        payload.put("companyName", parts[1]);
        List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
        addTencentMetric(metrics, "PE ratio", parts[39], "ratio");
        addTencentMetric(metrics, "PB ratio", parts.length > 46 ? parts[46] : "N/A", "ratio");
        addTencentMetric(metrics, "Total market cap", parts[44], "CNY");
        addTencentMetric(metrics, "Circulating market cap", parts[45], "CNY");
        payload.put("metrics", metrics);
        payload.put("asOf", parts[30]);
        payload.put("isRealtime", Boolean.TRUE);
        payload.put("delayHint", "Tencent Finance valuation metrics.");
        payload.put("sources", Collections.singletonList(source("Tencent Finance", url, "financials")));
        return payload;
    }

    private void addTencentMetric(List<Map<String, Object>> metrics, String label, String value, String unit) {
        Map<String, Object> metric = new LinkedHashMap<String, Object>();
        metric.put("metric", label);
        metric.put("label", label);
        metric.put("value", value);
        metric.put("unit", unit);
        metric.put("filed", "");
        metric.put("end", "");
        metric.put("form", "tencent-realtime");
        metrics.add(metric);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private double number(JsonNode node) {
        return node == null || !node.isNumber() ? 0 : node.asDouble();
    }

    private Number latestNumber(JsonNode values) {
        if (!values.isArray()) {
            return null;
        }
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i).isNumber()) {
                return values.get(i).numberValue();
            }
        }
        return null;
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ex) {
            return 0;
        }
    }

    private double parsePercent(String value) {
        return parseDouble(value == null ? "" : value.replace("%", ""));
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private String epoch(JsonNode node) {
        long epoch = node == null || !node.isNumber() ? 0 : node.asLong();
        if (epoch <= 0) {
            return nowIso();
        }
        return Instant.ofEpochSecond(epoch).toString();
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.asText();
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nowIso() {
        return Instant.now().toString();
    }

    private Map<String, Object> enrichContract(Map<String, Object> payload) {
        if (payload == null) {
            return new LinkedHashMap<String, Object>();
        }
        payload.put("timezone", CONTRACT_TIMEZONE);
        if (!payload.containsKey("asOf") || string(payload.get("asOf")).isEmpty()) {
            payload.put("asOf", payload.containsKey("retrievedAt") ? payload.get("retrievedAt") : nowIso());
        }
        payload.put("asOfLocal", localTime(string(payload.get("asOf"))));
        if (!payload.containsKey("delayHint")) {
            payload.put("delayHint", "Data latency depends on provider availability.");
        }
        Object metrics = payload.get("metrics");
        if (metrics instanceof List) {
            for (Object item : (List<?>) metrics) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metric = (Map<String, Object>) item;
                    String key = string(metric.get("metric"));
                    metric.put("label", metricLabel(key.isEmpty() ? string(metric.get("label")) : key));
                }
            }
        }
        return payload;
    }

    private String localTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        try {
            return Instant.parse(value).atZone(ZoneId.of(CONTRACT_TIMEZONE)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneId.of(CONTRACT_TIMEZONE)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (Exception ex) {
                return value;
            }
        }
    }

    private String metricLabel(String metric) {
        if (metric == null) {
            return "";
        }
        if ("Revenues".equals(metric) || "RevenueFromContractWithCustomerExcludingAssessedTax".equals(metric) || "SalesRevenueNet".equals(metric) || "revenue".equals(metric) || "RevenueTTM".equals(metric)) {
            return "Revenue";
        }
        if ("NetIncomeLoss".equals(metric) || "netIncome".equals(metric)) {
            return "Net income";
        }
        if ("OperatingIncomeLoss".equals(metric) || "operatingIncome".equals(metric)) {
            return "Operating income";
        }
        if ("EarningsPerShareDiluted".equals(metric) || "epsdiluted".equals(metric) || "EPS".equals(metric)) {
            return "Diluted EPS";
        }
        if ("Assets".equals(metric)) {
            return "Assets";
        }
        if ("Liabilities".equals(metric)) {
            return "Liabilities";
        }
        if ("StockholdersEquity".equals(metric)) {
            return "Stockholders equity";
        }
        if ("NetCashProvidedByUsedInOperatingActivities".equals(metric)) {
            return "Operating cash flow";
        }
        return metric;
    }

    private String childText(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || nodes.item(0) == null || nodes.item(0).getTextContent() == null) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }

    private String normalizeRssDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString();
        } catch (Exception ex) {
            return value;
        }
    }

    private interface SupplierMap {
        Map<String, Object> get();
    }

    private static class CacheEntry {
        private final Map<String, Object> value;
        private final long expiresAt;

        private CacheEntry(Map<String, Object> value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private static class MetricFact {
        private final Map<String, Object> values;

        private MetricFact(Map<String, Object> values) {
            this.values = values;
        }
    }
}
