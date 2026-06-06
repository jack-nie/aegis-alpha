package com.aegis.alpha.service;

import com.aegis.alpha.domain.PortfolioTrade;
import com.aegis.alpha.mapper.PortfolioTradeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioTradeServiceTest {
    private PortfolioTradeMapper mapper;
    private CacheService cacheService;
    private PortfolioTradeService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PortfolioTradeMapper.class);
        cacheService = mock(CacheService.class);
        service = new PortfolioTradeService(mapper, cacheService);
    }

    @Test
    void createSetsCalculatedFieldsForBuy() {
        PortfolioTrade trade = new PortfolioTrade();
        trade.setSymbol("AAPL");
        trade.setTradeDate("2026-01-01");
        trade.setSide("BUY");
        trade.setQuantity(new BigDecimal("100"));
        trade.setPrice(new BigDecimal("150.00"));
        trade.setFee(new BigDecimal("10.00"));

        PortfolioTrade result = service.create(trade);

        assertThat(result.getSymbol()).isEqualTo("AAPL");
        assertThat(result.getSide()).isEqualTo("BUY");
        assertThat(result.getGrossAmount()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(result.getNetAmount()).isEqualByComparingTo(new BigDecimal("15010.00"));
        assertThat(result.getCurrency()).isEqualTo("USD");
        assertThat(result.getSourceType()).isEqualTo("MANUAL");
        assertThat(result.getTradeId()).isNotNull();
        verify(cacheService).evict("aegis:dashboard:v1");
    }

    @Test
    void createSetsNetAmountForSell() {
        PortfolioTrade trade = new PortfolioTrade();
        trade.setSymbol("MSFT");
        trade.setTradeDate("2026-01-01");
        trade.setSide("SELL");
        trade.setQuantity(new BigDecimal("50"));
        trade.setPrice(new BigDecimal("300.00"));
        trade.setFee(new BigDecimal("15.00"));
        trade.setTax(new BigDecimal("5.00"));

        PortfolioTrade result = service.create(trade);

        assertThat(result.getGrossAmount()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(result.getNetAmount()).isEqualByComparingTo(new BigDecimal("14980.00"));
    }

    @Test
    void createThrowsOnMissingSymbol() {
        PortfolioTrade trade = new PortfolioTrade();
        trade.setTradeDate("2026-01-01");
        trade.setSide("BUY");
        trade.setQuantity(new BigDecimal("100"));
        trade.setPrice(new BigDecimal("150"));

        assertThatThrownBy(() -> service.create(trade))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol");
    }

    @Test
    void createThrowsOnMissingTradeDate() {
        PortfolioTrade trade = new PortfolioTrade();
        trade.setSymbol("AAPL");
        trade.setSide("BUY");
        trade.setQuantity(new BigDecimal("100"));
        trade.setPrice(new BigDecimal("150"));

        assertThatThrownBy(() -> service.create(trade))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tradeDate");
    }

    @Test
    void createThrowsOnInvalidSide() {
        PortfolioTrade trade = new PortfolioTrade();
        trade.setSymbol("AAPL");
        trade.setTradeDate("2026-01-01");
        trade.setSide("HOLD");
        trade.setQuantity(new BigDecimal("100"));
        trade.setPrice(new BigDecimal("150"));

        assertThatThrownBy(() -> service.create(trade))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BUY or SELL");
    }

    @Test
    void createThrowsOnZeroQuantity() {
        PortfolioTrade trade = new PortfolioTrade();
        trade.setSymbol("AAPL");
        trade.setTradeDate("2026-01-01");
        trade.setSide("BUY");
        trade.setQuantity(BigDecimal.ZERO);
        trade.setPrice(new BigDecimal("150"));

        assertThatThrownBy(() -> service.create(trade))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity must be positive");
    }

    @Test
    void createThrowsOnNullPrice() {
        PortfolioTrade trade = new PortfolioTrade();
        trade.setSymbol("AAPL");
        trade.setTradeDate("2026-01-01");
        trade.setSide("BUY");
        trade.setQuantity(new BigDecimal("100"));

        assertThatThrownBy(() -> service.create(trade))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price must be positive");
    }

    @Test
    void createThrowsOnNullTrade() {
        assertThatThrownBy(() -> service.create(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importRowsThrowsOnEmptyList() {
        assertThatThrownBy(() -> service.importRows(Collections.emptyList(), "batch-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importRowsThrowsOnNullList() {
        assertThatThrownBy(() -> service.importRows(null, "batch-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importRowsPreparesAndInsertsAllRows() {
        PortfolioTrade t1 = new PortfolioTrade();
        t1.setSymbol("AAPL");
        t1.setTradeDate("2026-01-01");
        t1.setSide("BUY");
        t1.setQuantity(new BigDecimal("100"));
        t1.setPrice(new BigDecimal("150"));

        PortfolioTrade t2 = new PortfolioTrade();
        t2.setSymbol("MSFT");
        t2.setTradeDate("2026-01-02");
        t2.setSide("BUY");
        t2.setQuantity(new BigDecimal("50"));
        t2.setPrice(new BigDecimal("300"));

        when(mapper.count()).thenReturn(0);

        java.util.List<PortfolioTrade> result = service.importRows(Arrays.asList(t1, t2), "batch-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSourceType()).isEqualTo("IMPORT");
        assertThat(result.get(0).getImportBatchId()).isEqualTo("batch-1");
        assertThat(result.get(1).getSourceType()).isEqualTo("IMPORT");
        verify(cacheService).evict("aegis:dashboard:v1");
    }

    @Test
    void createGeneratesTradeIdIfMissing() {
        PortfolioTrade trade = new PortfolioTrade();
        trade.setSymbol("AAPL");
        trade.setTradeDate("2026-01-01");
        trade.setSide("BUY");
        trade.setQuantity(new BigDecimal("10"));
        trade.setPrice(new BigDecimal("100"));

        PortfolioTrade result = service.create(trade);

        assertThat(result.getTradeId()).isNotNull();
        assertThat(result.getTradeId()).isNotEmpty();
    }

    @Test
    void createUsesProvidedTradeId() {
        PortfolioTrade trade = new PortfolioTrade();
        trade.setTradeId("custom-id");
        trade.setSymbol("AAPL");
        trade.setTradeDate("2026-01-01");
        trade.setSide("BUY");
        trade.setQuantity(new BigDecimal("10"));
        trade.setPrice(new BigDecimal("100"));

        PortfolioTrade result = service.create(trade);

        assertThat(result.getTradeId()).isEqualTo("custom-id");
    }

    @Test
    void deleteReturnsTrueWhenDeleted() {
        when(mapper.delete("t1")).thenReturn(1);

        assertThat(service.delete("t1")).isTrue();
        verify(cacheService).evict("aegis:dashboard:v1");
    }

    @Test
    void deleteReturnsFalseWhenNotFound() {
        when(mapper.delete("missing")).thenReturn(0);

        assertThat(service.delete("missing")).isFalse();
    }

    @Test
    void countDelegatesToMapper() {
        when(mapper.count()).thenReturn(42);

        assertThat(service.count()).isEqualTo(42);
    }
}