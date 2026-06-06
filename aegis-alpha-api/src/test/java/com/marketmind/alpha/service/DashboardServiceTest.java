package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.*;
import com.marketmind.alpha.mapper.DashboardMapper;
import com.marketmind.alpha.mapper.PortfolioMapper;
import com.marketmind.alpha.mapper.PortfolioTradeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DashboardServiceTest {
    private DashboardMapper dashboardMapper;
    private PortfolioMapper portfolioMapper;
    private PortfolioTradeMapper portfolioTradeMapper;
    private CacheService cacheService;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        dashboardMapper = mock(DashboardMapper.class);
        portfolioMapper = mock(PortfolioMapper.class);
        portfolioTradeMapper = mock(PortfolioTradeMapper.class);
        cacheService = mock(CacheService.class);
        service = new DashboardService(dashboardMapper, portfolioMapper, portfolioTradeMapper, cacheService);
    }

    @SuppressWarnings("unchecked")
    @Test
    void dashboardReturnsFullPayload() {
        when(cacheService.get("marketmind:dashboard:v1", Map.class)).thenReturn(null);
        when(dashboardMapper.quadrantRows()).thenReturn(Collections.emptyList());
        when(dashboardMapper.creditRows()).thenReturn(Collections.emptyList());
        when(dashboardMapper.indicators()).thenReturn(Collections.emptyList());
        MarketSnapshot ms = new MarketSnapshot();
        ms.setName("Apple");
        ms.setSymbol("AAPL");
        ms.setChangePct(new BigDecimal("1.5"));
        when(dashboardMapper.markets()).thenReturn(List.of(ms));
        when(portfolioMapper.count()).thenReturn(3);
        when(portfolioTradeMapper.count()).thenReturn(24);

        Map<String, Object> result = service.dashboard();

        assertThat(result).containsKey("quadrantRows");
        assertThat(result).containsKey("creditRows");
        assertThat(result).containsKey("indicators");
        assertThat(result).containsKey("markets");
        assertThat(result).containsKey("marketBreadth");
        assertThat(result).containsKey("counts");
        assertThat(result).containsKey("daily");

        Map<String, Object> breadth = (Map<String, Object>) result.get("marketBreadth");
        assertThat(breadth.get("up")).isEqualTo(1);
        assertThat(breadth.get("down")).isEqualTo(0);

        Map<String, Object> counts = (Map<String, Object>) result.get("counts");
        assertThat(counts).containsKey("浜ゆ槗");
        assertThat(counts.get("浜ゆ槗")).isEqualTo(24);
    }

    @SuppressWarnings("unchecked")
    @Test
    void dashboardCacheHitReturnsCachedValue() {
        Map<String, Object> cached = new HashMap<>();
        cached.put("cached", true);
        when(cacheService.get("marketmind:dashboard:v1", Map.class)).thenReturn(cached);

        Map<String, Object> result = service.dashboard();

        assertThat(result).containsEntry("cached", true);
        verifyNoInteractions(dashboardMapper);
    }

    @SuppressWarnings("unchecked")
    @Test
    void marketBreadthCountsDownMarkets() {
        when(cacheService.get("marketmind:dashboard:v1", Map.class)).thenReturn(null);
        when(dashboardMapper.quadrantRows()).thenReturn(Collections.emptyList());
        when(dashboardMapper.creditRows()).thenReturn(Collections.emptyList());
        when(dashboardMapper.indicators()).thenReturn(Collections.emptyList());

        MarketSnapshot up = new MarketSnapshot();
        up.setName("Apple");
        up.setSymbol("AAPL");
        up.setChangePct(new BigDecimal("1.5"));
        MarketSnapshot down = new MarketSnapshot();
        down.setName("Meta");
        down.setSymbol("META");
        down.setChangePct(new BigDecimal("-0.5"));

        when(dashboardMapper.markets()).thenReturn(List.of(up, down));
        when(portfolioMapper.count()).thenReturn(0);
        when(portfolioTradeMapper.count()).thenReturn(0);

        Map<String, Object> result = service.dashboard();
        Map<String, Object> breadth = (Map<String, Object>) result.get("marketBreadth");

        assertThat(breadth.get("up")).isEqualTo(1);
        assertThat(breadth.get("down")).isEqualTo(1);
        assertThat(breadth.get("upRatio")).isEqualTo(50L);
    }
}