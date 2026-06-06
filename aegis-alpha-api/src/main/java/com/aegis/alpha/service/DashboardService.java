package com.aegis.alpha.service;

import com.aegis.alpha.domain.MarketSnapshot;
import com.aegis.alpha.mapper.DashboardMapper;
import com.aegis.alpha.mapper.PortfolioMapper;
import com.aegis.alpha.mapper.PortfolioTradeMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {
    private static final String CACHE_KEY = "aegis:dashboard:v1";
    private final DashboardMapper dashboardMapper;
    private final PortfolioMapper portfolioMapper;
    private final PortfolioTradeMapper portfolioTradeMapper;
    private final CacheService cacheService;

    public DashboardService(DashboardMapper dashboardMapper, PortfolioMapper portfolioMapper, PortfolioTradeMapper portfolioTradeMapper, CacheService cacheService) {
        this.dashboardMapper = dashboardMapper;
        this.portfolioMapper = portfolioMapper;
        this.portfolioTradeMapper = portfolioTradeMapper;
        this.cacheService = cacheService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> dashboard() {
        Map cached = cacheService.get(CACHE_KEY, Map.class);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("quadrantRows", rows(dashboardMapper.quadrantRows(), "row_date"));
        payload.put("creditRows", rows(dashboardMapper.creditRows(), "period"));
        payload.put("indicators", dashboardMapper.indicators());
        List<MarketSnapshot> markets = dashboardMapper.markets();
        payload.put("markets", markets);

        int up = 0;
        for (MarketSnapshot market : markets) {
            if (market.getChangePct().signum() >= 0) {
                up++;
            }
        }
        Map<String, Object> breadth = new HashMap<String, Object>();
        breadth.put("up", up);
        breadth.put("down", markets.size() - up);
        breadth.put("upRatio", markets.isEmpty() ? 0 : Math.round(up * 100.0 / markets.size()));
        payload.put("marketBreadth", breadth);

        Map<String, Object> counts = new HashMap<String, Object>();
        counts.put("缁勫�?, portfolioMapper.count());
        counts.put("璧勪�?, 0);
        counts.put("浜ゆ�?, portfolioTradeMapper.count());
        counts.put("鏈熸潈缁勫悎", 0);
        payload.put("counts", counts);

        Map<String, Object> daily = new HashMap<String, Object>();
        daily.put("status", "Idle");
        daily.put("reports", new ArrayList<Object>());
        payload.put("daily", daily);

        cacheService.put(CACHE_KEY, payload, Duration.ofSeconds(60));
        return payload;
    }

    private List<List<Object>> rows(List<Map<String, Object>> rows, String dateKey) {
        List<List<Object>> result = new ArrayList<List<Object>>();
        for (Map<String, Object> row : rows) {
            List<Object> values = new ArrayList<Object>();
            values.add(value(row, dateKey));
            values.add(value(row, "china"));
            values.add(value(row, "usa"));
            values.add(value(row, "europe"));
            values.add(value(row, "japan"));
            result.add(values);
        }
        return result;
    }

    private Object value(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        return row.get(key.toUpperCase());
    }
}
