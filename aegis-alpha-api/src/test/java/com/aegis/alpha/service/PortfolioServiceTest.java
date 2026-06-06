package com.aegis.alpha.service;

import com.aegis.alpha.domain.Portfolio;
import com.aegis.alpha.domain.PortfolioTrade;
import com.aegis.alpha.mapper.PortfolioMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PortfolioServiceTest {
    private PortfolioMapper mapper;
    private CacheService cacheService;
    private PortfolioTradeService tradeService;
    private PortfolioService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PortfolioMapper.class);
        cacheService = mock(CacheService.class);
        tradeService = mock(PortfolioTradeService.class);
        service = new PortfolioService(mapper, cacheService, tradeService);
    }

    @Test
    void findAllDelegatesToMapper() {
        when(mapper.findAll()).thenReturn(Collections.emptyList());
        assertThat(service.findAll()).isEmpty();
    }

    @Test
    void createSeedsPortfolioWithDefaults() {
        when(mapper.count()).thenReturn(0);

        Portfolio result = service.create("My Portfolio");

        assertThat(result.getName()).isEqualTo("My Portfolio");
        assertThat(result.getNav()).isEqualByComparingTo(new BigDecimal("250000"));
        assertThat(result.getReturnPct()).isEqualByComparingTo(new BigDecimal("4.20"));
        verify(mapper).insert(any(Portfolio.class));
        verify(cacheService).evict("aegis:dashboard:v1");
    }

    @Test
    void createUsesDefaultNameIfNull() {
        when(mapper.count()).thenReturn(2);

        Portfolio result = service.create(null);

        assertThat(result.getName()).isEqualTo("Portfolio 3");
    }

    @Test
    void createUsesDefaultNameIfEmpty() {
        when(mapper.count()).thenReturn(0);

        Portfolio result = service.create("  ");

        assertThat(result.getName()).isEqualTo("Portfolio 1");
    }

    @Test
    void summaryContractReturnsNoPortfolioIfNull() {
        when(mapper.findById("missing")).thenReturn(null);
        when(tradeService.findAll("missing", null)).thenReturn(Collections.emptyList());

        Map<String, Object> contract = service.summaryContract("missing");

        assertThat(contract.get("portfolioFound")).isEqualTo(false);
        assertThat(contract.get("dataCompleteness")).isEqualTo("NO_PORTFOLIO");
    }

    @Test
    void summaryContractReturnsSeededSummaryOnlyIfNoTrades() {
        Portfolio portfolio = new Portfolio();
        portfolio.setId("p-1");
        portfolio.setName("Test");
        portfolio.setNav(new BigDecimal("100000"));
        portfolio.setAssets(5);
        portfolio.setTransactions(12);
        when(mapper.findById("p-1")).thenReturn(portfolio);
        when(tradeService.findAll("p-1", null)).thenReturn(Collections.emptyList());

        Map<String, Object> contract = service.summaryContract("p-1");

        assertThat(contract.get("portfolioFound")).isEqualTo(true);
        assertThat(contract.get("dataCompleteness")).isEqualTo("SEEDED_SUMMARY_ONLY");
    }

    @Test
    void summaryContractReturnsDetailsSyncedWithTrades() {
        Portfolio portfolio = new Portfolio();
        portfolio.setId("p-1");
        portfolio.setNav(new BigDecimal("100000"));
        portfolio.setReturnPct(new BigDecimal("5.0"));
        when(mapper.findById("p-1")).thenReturn(portfolio);

        PortfolioTrade trade = new PortfolioTrade();
        trade.setSymbol("AAPL");
        trade.setSide("BUY");
        trade.setQuantity(new BigDecimal("100"));
        trade.setNetAmount(new BigDecimal("15000"));
        trade.setGrossAmount(new BigDecimal("15000"));
        when(tradeService.findAll("p-1", null)).thenReturn(Arrays.asList(trade));

        Map<String, Object> contract = service.summaryContract("p-1");

        assertThat(contract.get("dataCompleteness")).isEqualTo("DETAILS_SYNCED");
        assertThat(contract.get("tradeCount")).isEqualTo(1);
    }

    @Test
    void positionsContractAggregatesBySymbol() {
        Portfolio portfolio = new Portfolio();
        portfolio.setId("p-1");
        when(mapper.findById("p-1")).thenReturn(portfolio);

        PortfolioTrade buy = new PortfolioTrade();
        buy.setSymbol("AAPL");
        buy.setSecurityName("Apple Inc.");
        buy.setCurrency("USD");
        buy.setSide("BUY");
        buy.setQuantity(new BigDecimal("100"));
        buy.setNetAmount(new BigDecimal("15000"));
        buy.setGrossAmount(new BigDecimal("15000"));

        PortfolioTrade sell = new PortfolioTrade();
        sell.setSymbol("AAPL");
        sell.setSecurityName("Apple Inc.");
        sell.setCurrency("USD");
        sell.setSide("SELL");
        sell.setQuantity(new BigDecimal("30"));
        sell.setNetAmount(new BigDecimal("4500"));
        sell.setGrossAmount(new BigDecimal("4500"));

        when(tradeService.findAll("p-1", null)).thenReturn(Arrays.asList(buy, sell));

        Map<String, Object> contract = service.positionsContract("p-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> positions = (List<Map<String, Object>>) contract.get("positions");
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).get("symbol")).isEqualTo("AAPL");
        assertThat(positions.get(0).get("quantity")).isEqualTo(new BigDecimal("70"));
        assertThat(contract.get("positionCount")).isEqualTo(1);
    }
}