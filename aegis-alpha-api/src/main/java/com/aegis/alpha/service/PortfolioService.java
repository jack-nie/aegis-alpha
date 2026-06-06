package com.aegis.alpha.service;

import com.aegis.alpha.domain.Portfolio;
import com.aegis.alpha.domain.PortfolioTrade;
import com.aegis.alpha.mapper.PortfolioMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PortfolioService {
    private final PortfolioMapper mapper;
    private final CacheService cacheService;
    private final PortfolioTradeService tradeService;

    public PortfolioService(PortfolioMapper mapper, CacheService cacheService, PortfolioTradeService tradeService) {
        this.mapper = mapper;
        this.cacheService = cacheService;
        this.tradeService = tradeService;
    }

    public List<Portfolio> findAll() {
        return mapper.findAll();
    }

    public Map<String, Object> summaryContract(String portfolioId) {
        Portfolio portfolio = mapper.findById(portfolioId);
        List<PortfolioTrade> trades = tradeService.findAll(portfolioId, null);
        Map<String, Object> contract = baseContract(portfolio, portfolioId, trades, positions(trades));
        contract.put("summary", summary(portfolio, trades));
        return contract;
    }

    public Map<String, Object> positionsContract(String portfolioId) {
        Portfolio portfolio = mapper.findById(portfolioId);
        List<PortfolioTrade> trades = tradeService.findAll(portfolioId, null);
        List<Map<String, Object>> positions = positions(trades);
        Map<String, Object> contract = baseContract(portfolio, portfolioId, trades, positions);
        contract.put("positions", positions);
        return contract;
    }

    public Map<String, Object> tradesContract(String portfolioId) {
        Portfolio portfolio = mapper.findById(portfolioId);
        List<PortfolioTrade> trades = tradeService.findAll(portfolioId, null);
        Map<String, Object> contract = baseContract(portfolio, portfolioId, trades, positions(trades));
        contract.put("trades", trades);
        return contract;
    }

    public Portfolio create(String name) {
        int count = mapper.count();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(UUID.randomUUID().toString());
        portfolio.setName(name == null || name.trim().isEmpty() ? "Portfolio " + (count + 1) : name.trim());
        portfolio.setNav(new BigDecimal("250000").add(new BigDecimal(count * 37500)));
        portfolio.setReturnPct(new BigDecimal("4.20").add(new BigDecimal(count).multiply(new BigDecimal("0.80"))));
        portfolio.setAssets(5 + count);
        portfolio.setTransactions(12 + count * 3);
        portfolio.setOptionCombos(count % 2);
        portfolio.setUpdatedAt(LocalDate.now().toString());
        mapper.insert(portfolio);
        cacheService.evict("aegis:dashboard:v1");
        return portfolio;
    }

    private Map<String, Object> baseContract(Portfolio portfolio, String portfolioId, List<PortfolioTrade> trades, List<Map<String, Object>> positions) {
        Map<String, Object> contract = new LinkedHashMap<String, Object>();
        contract.put("portfolioId", portfolioId);
        contract.put("asOf", LocalDateTime.now().toString());
        String completeness = dataCompleteness(portfolio, trades, positions);
        contract.put("dataCompleteness", completeness);
        contract.put("sourceStatus", sourceStatus(completeness));
        contract.put("portfolioFound", portfolio != null);
        contract.put("tradeCount", trades.size());
        contract.put("positionCount", positions.size());
        return contract;
    }

    private Map<String, Object> summary(Portfolio portfolio, List<PortfolioTrade> trades) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        if (portfolio != null) {
            summary.put("id", portfolio.getId());
            summary.put("name", portfolio.getName());
            summary.put("nav", portfolio.getNav());
            summary.put("returnPct", portfolio.getReturnPct());
            summary.put("seededAssets", portfolio.getAssets());
            summary.put("seededTransactions", portfolio.getTransactions());
            summary.put("optionCombos", portfolio.getOptionCombos());
            summary.put("updatedAt", portfolio.getUpdatedAt());
        }
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal netCashFlow = BigDecimal.ZERO;
        for (PortfolioTrade trade : trades) {
            gross = gross.add(zero(trade.getGrossAmount()));
            netCashFlow = "SELL".equals(trade.getSide())
                    ? netCashFlow.add(zero(trade.getNetAmount()))
                    : netCashFlow.subtract(zero(trade.getNetAmount()));
        }
        summary.put("tradeCount", trades.size());
        summary.put("grossAmount", gross);
        summary.put("netCashFlow", netCashFlow);
        return summary;
    }

    private List<Map<String, Object>> positions(List<PortfolioTrade> trades) {
        Map<String, Map<String, Object>> bySymbol = new LinkedHashMap<String, Map<String, Object>>();
        for (PortfolioTrade trade : trades) {
            String symbol = trade.getSymbol();
            if (symbol == null || symbol.trim().isEmpty()) {
                continue;
            }
            Map<String, Object> row = bySymbol.get(symbol);
            if (row == null) {
                row = new LinkedHashMap<String, Object>();
                row.put("symbol", symbol);
                row.put("securityName", trade.getSecurityName());
                row.put("currency", trade.getCurrency());
                row.put("quantity", BigDecimal.ZERO);
                row.put("costBasis", BigDecimal.ZERO);
                row.put("tradeCount", 0);
                bySymbol.put(symbol, row);
            }
            BigDecimal quantity = (BigDecimal) row.get("quantity");
            BigDecimal costBasis = (BigDecimal) row.get("costBasis");
            BigDecimal tradeQuantity = zero(trade.getQuantity());
            if ("SELL".equals(trade.getSide())) {
                quantity = quantity.subtract(tradeQuantity);
                costBasis = costBasis.subtract(zero(trade.getNetAmount()));
            } else {
                quantity = quantity.add(tradeQuantity);
                costBasis = costBasis.add(zero(trade.getNetAmount()));
            }
            row.put("quantity", quantity);
            row.put("costBasis", costBasis);
            row.put("tradeCount", ((Integer) row.get("tradeCount")) + 1);
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : bySymbol.values()) {
            BigDecimal quantity = (BigDecimal) row.get("quantity");
            if (quantity.compareTo(BigDecimal.ZERO) != 0) {
                result.add(row);
            }
        }
        return result;
    }

    private String dataCompleteness(Portfolio portfolio, List<PortfolioTrade> trades, List<Map<String, Object>> positions) {
        if (portfolio == null) {
            return "NO_PORTFOLIO";
        }
        if (trades.isEmpty() && (number(portfolio.getAssets()) > 0 || number(portfolio.getTransactions()) > 0)) {
            return "SEEDED_SUMMARY_ONLY";
        }
        if (trades.isEmpty() || positions.isEmpty()) {
            return "NO_OPEN_POSITIONS";
        }
        return "DETAILS_SYNCED";
    }

    private String sourceStatus(String dataCompleteness) {
        if ("NO_PORTFOLIO".equals(dataCompleteness)) {
            return "组合不存在";
        }
        if ("SEEDED_SUMMARY_ONLY".equals(dataCompleteness)) {
            return "摘要已导入，交易与持仓明细待同步";
        }
        if ("NO_OPEN_POSITIONS".equals(dataCompleteness)) {
            return "交易明细已同步，当前没有未平仓仓位";
        }
        return "明细已同步";
    }

    private int number(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
