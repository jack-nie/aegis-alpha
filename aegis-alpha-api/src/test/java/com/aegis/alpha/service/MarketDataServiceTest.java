package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MarketDataServiceTest {
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    private final MarketDataService service = new MarketDataService(
            restTemplate,
            new ObjectMapper(),
            "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1m&range=1d",
            "https://stooq.com/q/l/?s={symbol}.us&f=sd2t2ohlcv&h&e=csv",
            "https://www.sec.gov/files/company_tickers.json",
            "https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json",
            "https://feeds.finance.yahoo.com/rss/2.0/headline?s={symbol}&region=US&lang=en-US",
            "https://api.gdeltproject.org/api/v2/doc/doc?query={symbol}&mode=artlist&format=json&maxrecords=10",
            "",
            "",
            "",
            "",
            "",
            "");

    @Test
    void defaultConstructorConfiguresFiniteHttpTimeouts() {
        MarketDataService defaultService = new MarketDataService(
                new ObjectMapper(),
                "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1m&range=1d",
                "https://stooq.com/q/l/?s={symbol}.us&f=sd2t2ohlcv&h&e=csv",
                "https://www.sec.gov/files/company_tickers.json",
                "https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json",
                "https://feeds.finance.yahoo.com/rss/2.0/headline?s={symbol}&region=US&lang=en-US",
                "https://api.gdeltproject.org/api/v2/doc/doc?query={symbol}&mode=artlist&format=json&maxrecords=10",
                "",
                "",
                "",
                "",
                "",
                "",
                4000);

        RestTemplate configured = (RestTemplate) ReflectionTestUtils.getField(defaultService, "restTemplate");
        assertThat(configured.getRequestFactory()).isInstanceOf(SimpleClientHttpRequestFactory.class);
        Object factory = configured.getRequestFactory();
        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(4000);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(4000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void quoteUsesYahooChartAndCarriesProviderMetadata() {
        server.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d"))
                .andRespond(withSuccess("{\"chart\":{\"result\":[{\"meta\":{\"symbol\":\"AAPL\",\"regularMarketPrice\":205.35,\"previousClose\":200.00,\"regularMarketTime\":1777896000,\"currency\":\"USD\",\"exchangeName\":\"NMS\",\"longName\":\"Apple Inc.\"},\"timestamp\":[1777895700,1777896000],\"indicators\":{\"quote\":[{\"open\":[204.1],\"high\":[206.2],\"low\":[203.8],\"close\":[205.35],\"volume\":[12345678]}]}}],\"error\":null}}", MediaType.APPLICATION_JSON));

        Map<String, Object> quote = service.quote("AAPL");

        assertThat(quote).containsEntry("symbol", "AAPL");
        assertThat(quote).containsEntry("provider", "yahoo-chart");
        assertThat(quote).containsEntry("currency", "USD");
        assertThat((Number) quote.get("price")).isEqualTo(205.35);
        assertThat((Number) quote.get("change")).isEqualTo(5.35);
        assertThat((Number) quote.get("changePct")).isEqualTo(2.675);
        assertThat(quote.get("asOf")).isEqualTo("2026-05-04T12:00:00Z");
        assertThat(quote.get("isRealtime")).isEqualTo(Boolean.TRUE);
        assertThat((List<Map<String, Object>>) quote.get("sources")).hasSize(1);
    }

    @Test
    void quoteCanUseAlphaVantageWhenApiKeyIsConfigured() {
        MarketDataService keyedService = new MarketDataService(
                restTemplate,
                new ObjectMapper(),
                "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1m&range=1d",
                "https://stooq.com/q/l/?s={symbol}.us&f=sd2t2ohlcv&h&e=csv",
                "https://www.sec.gov/files/company_tickers.json",
                "https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json",
                "https://feeds.finance.yahoo.com/rss/2.0/headline?s={symbol}&region=US&lang=en-US",
                "https://api.gdeltproject.org/api/v2/doc/doc?query={symbol}&mode=artlist&format=json&maxrecords=10",
                "",
                "alpha-key",
                "",
                "",
                "",
                "");
        server.expect(requestTo("https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=AAPL&apikey=alpha-key"))
                .andRespond(withSuccess("{\"Global Quote\":{\"01. symbol\":\"AAPL\",\"05. price\":\"205.3500\",\"08. previous close\":\"200.0000\",\"09. change\":\"5.3500\",\"10. change percent\":\"2.6750%\"}}", MediaType.APPLICATION_JSON));

        Map<String, Object> quote = keyedService.quote("AAPL");

        assertThat(quote).containsEntry("provider", "alpha-vantage");
        assertThat((Number) quote.get("price")).isEqualTo(205.35);
        assertThat((Number) quote.get("changePct")).isEqualTo(2.675);
    }

    @Test
    @SuppressWarnings("unchecked")
    void financialsUseSecCompanyFactsAndNormalizeMetrics() {
        server.expect(requestTo("https://www.sec.gov/files/company_tickers.json"))
                .andRespond(withSuccess("{\"0\":{\"cik_str\":320193,\"ticker\":\"AAPL\",\"title\":\"Apple Inc.\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json"))
                .andRespond(withSuccess("{\"cik\":320193,\"entityName\":\"Apple Inc.\",\"facts\":{\"us-gaap\":{\"Revenues\":{\"units\":{\"USD\":[{\"fy\":2025,\"fp\":\"FY\",\"form\":\"10-K\",\"filed\":\"2025-10-31\",\"end\":\"2025-09-27\",\"val\":391035000000}]}},\"NetIncomeLoss\":{\"units\":{\"USD\":[{\"fy\":2025,\"fp\":\"FY\",\"form\":\"10-K\",\"filed\":\"2025-10-31\",\"end\":\"2025-09-27\",\"val\":93736000000}]}}}}}", MediaType.APPLICATION_JSON));

        Map<String, Object> financials = service.financials("AAPL");

        assertThat(financials).containsEntry("symbol", "AAPL");
        assertThat(financials).containsEntry("provider", "sec-companyfacts");
        assertThat(financials).containsEntry("companyName", "Apple Inc.");
        assertThat((List<Map<String, Object>>) financials.get("metrics"))
                .extracting(row -> row.get("metric"))
                .contains("Revenues", "NetIncomeLoss");
        assertThat(financials.get("asOf")).isEqualTo("2025-10-31");
    }

    @Test
    @SuppressWarnings("unchecked")
    void financialsFallBackToFmpWhenSecIsUnavailableAndApiKeyExists() {
        MarketDataService keyedService = new MarketDataService(
                restTemplate,
                new ObjectMapper(),
                "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1m&range=1d",
                "https://stooq.com/q/l/?s={symbol}.us&f=sd2t2ohlcv&h&e=csv",
                "https://www.sec.gov/files/company_tickers.json",
                "https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json",
                "https://feeds.finance.yahoo.com/rss/2.0/headline?s={symbol}&region=US&lang=en-US",
                "https://api.gdeltproject.org/api/v2/doc/doc?query={symbol}&mode=artlist&format=json&maxrecords=10",
                "",
                "",
                "fmp-key",
                "",
                "",
                "");
        server.expect(requestTo("https://www.sec.gov/files/company_tickers.json")).andRespond(withServerError());
        server.expect(requestTo("https://financialmodelingprep.com/api/v3/income-statement/AAPL?period=annual&limit=1&apikey=fmp-key"))
                .andRespond(withSuccess("[{\"symbol\":\"AAPL\",\"date\":\"2025-09-27\",\"fillingDate\":\"2025-10-31\",\"reportedCurrency\":\"USD\",\"revenue\":391035000000,\"netIncome\":93736000000,\"operatingIncome\":123216000000,\"epsdiluted\":6.11}]", MediaType.APPLICATION_JSON));

        Map<String, Object> financials = keyedService.financials("AAPL");

        assertThat(financials).containsEntry("provider", "fmp-income-statement");
        assertThat((List<Map<String, Object>>) financials.get("metrics"))
                .extracting(row -> row.get("metric"))
                .contains("revenue", "netIncome", "operatingIncome", "epsdiluted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void newsUsesYahooFinanceRssAndNormalizesArticles() {
        server.expect(requestTo("https://feeds.finance.yahoo.com/rss/2.0/headline?s=AAPL&region=US&lang=en-US"))
                .andRespond(withSuccess("<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><item><title>Apple updates guidance</title><link>https://finance.yahoo.com/aapl-guidance</link><pubDate>Mon, 04 May 2026 12:00:00 GMT</pubDate><source>Yahoo Finance</source></item></channel></rss>", MediaType.APPLICATION_XML));

        Map<String, Object> news = service.news("AAPL");

        assertThat(news).containsEntry("symbol", "AAPL");
        assertThat(news).containsEntry("provider", "yahoo-finance-rss");
        List<Map<String, Object>> articles = (List<Map<String, Object>>) news.get("articles");
        assertThat(articles).hasSize(1);
        assertThat(articles.get(0)).containsEntry("title", "Apple updates guidance");
        assertThat(articles.get(0)).containsEntry("source", "Yahoo Finance");
    }

    @Test
    @SuppressWarnings("unchecked")
    void yahooRssParsesUtf8BytesEvenWhenHttpCharsetIsWrong() {
        String rss = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><item><title>Microsoft’s AI push</title><link>https://finance.yahoo.com/msft-ai</link><pubDate>Mon, 04 May 2026 12:00:00 GMT</pubDate><source>Yahoo Finance</source></item></channel></rss>";
        server.expect(requestTo("https://feeds.finance.yahoo.com/rss/2.0/headline?s=MSFT&region=US&lang=en-US"))
                .andRespond(withSuccess(rss.getBytes(StandardCharsets.UTF_8), MediaType.parseMediaType("application/rss+xml;charset=ISO-8859-1")));

        Map<String, Object> news = service.news("MSFT");

        List<Map<String, Object>> articles = (List<Map<String, Object>>) news.get("articles");
        assertThat(articles).hasSize(1);
        assertThat(articles.get(0)).containsEntry("title", "Microsoft’s AI push");
    }
}
