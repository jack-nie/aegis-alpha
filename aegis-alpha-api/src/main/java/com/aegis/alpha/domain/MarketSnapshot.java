package com.aegis.alpha.domain;

import java.math.BigDecimal;

public class MarketSnapshot {
    private String name;
    private String symbol;
    private BigDecimal changePct;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public BigDecimal getChangePct() { return changePct; }
    public void setChangePct(BigDecimal changePct) { this.changePct = changePct; }
}
