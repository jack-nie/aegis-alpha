package com.aegis.alpha.service;

import com.aegis.alpha.domain.PortfolioTrade;
import com.aegis.alpha.mapper.PortfolioTradeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PortfolioTradeService {
    private static final String DASHBOARD_CACHE_KEY = "aegis:dashboard:v1";
    private final PortfolioTradeMapper mapper;
    private final CacheService cacheService;

    public PortfolioTradeService(PortfolioTradeMapper mapper, CacheService cacheService) {
        this.mapper = mapper;
        this.cacheService = cacheService;
    }

    public List<PortfolioTrade> findAll(String portfolioId, String symbol) {
        return mapper.findAll(trimToNull(portfolioId), trimToNull(symbol));
    }

    public int count() {
        return mapper.count();
    }

    public PortfolioTrade create(PortfolioTrade trade) {
        PortfolioTrade prepared = prepare(trade, "MANUAL", null);
        mapper.insert(prepared);
        cacheService.evict(DASHBOARD_CACHE_KEY);
        return prepared;
    }

    @Transactional
    public List<PortfolioTrade> importRows(List<PortfolioTrade> rows, String importBatchId) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("rows is required");
        }
        String batchId = trimToNull(importBatchId);
        if (batchId == null) {
            batchId = UUID.randomUUID().toString();
        }
        List<PortfolioTrade> saved = new ArrayList<PortfolioTrade>();
        for (PortfolioTrade row : rows) {
            PortfolioTrade prepared = prepare(row, "IMPORT", batchId);
            mapper.insert(prepared);
            saved.add(prepared);
        }
        cacheService.evict(DASHBOARD_CACHE_KEY);
        return saved;
    }

    public boolean delete(String tradeId) {
        int deleted = mapper.delete(tradeId);
        if (deleted > 0) {
            cacheService.evict(DASHBOARD_CACHE_KEY);
        }
        return deleted > 0;
    }

    private PortfolioTrade prepare(PortfolioTrade source, String defaultSourceType, String importBatchId) {
        if (source == null) {
            throw new IllegalArgumentException("trade is required");
        }
        String symbol = required(source.getSymbol(), "symbol");
        String tradeDate = required(source.getTradeDate(), "tradeDate");
        String side = required(source.getSide(), "side").toUpperCase();
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new IllegalArgumentException("side must be BUY or SELL");
        }
        BigDecimal quantity = requiredPositive(source.getQuantity(), "quantity");
        BigDecimal price = requiredPositive(source.getPrice(), "price");
        BigDecimal gross = quantity.multiply(price);
        BigDecimal totalFees = zero(source.getFee())
                .add(zero(source.getTax()))
                .add(zero(source.getCommission()))
                .add(zero(source.getOtherFee()));

        source.setTradeId(trimToNull(source.getTradeId()) == null ? UUID.randomUUID().toString() : source.getTradeId().trim());
        source.setSymbol(symbol.toUpperCase());
        source.setTradeDate(tradeDate);
        source.setSide(side);
        source.setQuantity(quantity);
        source.setPrice(price);
        source.setGrossAmount(gross);
        source.setFee(zero(source.getFee()));
        source.setTax(zero(source.getTax()));
        source.setCommission(zero(source.getCommission()));
        source.setOtherFee(zero(source.getOtherFee()));
        source.setNetAmount("BUY".equals(side) ? gross.add(totalFees) : gross.subtract(totalFees));
        source.setCurrency(defaultText(source.getCurrency(), "USD"));
        source.setFxRate(source.getFxRate() == null ? BigDecimal.ONE : source.getFxRate());
        source.setSourceType(defaultText(source.getSourceType(), defaultSourceType));
        source.setImportBatchId(importBatchId == null ? source.getImportBatchId() : importBatchId);
        String now = LocalDateTime.now().toString();
        source.setCreatedAt(trimToNull(source.getCreatedAt()) == null ? now : source.getCreatedAt());
        source.setUpdatedAt(now);
        return source;
    }

    private String required(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return trimmed;
    }

    private BigDecimal requiredPositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String defaultText(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
