package com.marketmind.alpha.domain;

import java.math.BigDecimal;

public class Portfolio {
    private String id;
    private String name;
    private BigDecimal nav;
    private BigDecimal returnPct;
    private Integer assets;
    private Integer transactions;
    private Integer optionCombos;
    private String updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getNav() { return nav; }
    public void setNav(BigDecimal nav) { this.nav = nav; }
    public BigDecimal getReturnPct() { return returnPct; }
    public void setReturnPct(BigDecimal returnPct) { this.returnPct = returnPct; }
    public Integer getAssets() { return assets; }
    public void setAssets(Integer assets) { this.assets = assets; }
    public Integer getTransactions() { return transactions; }
    public void setTransactions(Integer transactions) { this.transactions = transactions; }
    public Integer getOptionCombos() { return optionCombos; }
    public void setOptionCombos(Integer optionCombos) { this.optionCombos = optionCombos; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
