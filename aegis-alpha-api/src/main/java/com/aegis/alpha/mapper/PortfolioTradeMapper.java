package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.PortfolioTrade;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PortfolioTradeMapper {
    @Select("<script>" +
            "select trade_id, portfolio_id, trade_date, settlement_date, symbol, exchange, market, security_name, side, " +
            "quantity, price, gross_amount, fee, tax, commission, other_fee, net_amount, currency, fx_rate, broker, " +
            "account_no, strategy, trade_type, order_type, notes, source_type, import_batch_id, created_at, updated_at " +
            "from portfolio_trade " +
            "where 1 = 1 " +
            "<if test='portfolioId != null and portfolioId != \"\"'>and portfolio_id = #{portfolioId} </if>" +
            "<if test='symbol != null and symbol != \"\"'>and symbol = #{symbol} </if>" +
            "order by trade_date desc, created_at desc" +
            "</script>")
    List<PortfolioTrade> findAll(@Param("portfolioId") String portfolioId, @Param("symbol") String symbol);

    @Select("select count(*) from portfolio_trade")
    int count();

    @Insert("insert into portfolio_trade(trade_id, portfolio_id, trade_date, settlement_date, symbol, exchange, market, security_name, side, " +
            "quantity, price, gross_amount, fee, tax, commission, other_fee, net_amount, currency, fx_rate, broker, account_no, strategy, " +
            "trade_type, order_type, notes, source_type, import_batch_id, created_at, updated_at) " +
            "values(#{tradeId}, #{portfolioId}, #{tradeDate}, #{settlementDate}, #{symbol}, #{exchange}, #{market}, #{securityName}, #{side}, " +
            "#{quantity}, #{price}, #{grossAmount}, #{fee}, #{tax}, #{commission}, #{otherFee}, #{netAmount}, #{currency}, #{fxRate}, #{broker}, " +
            "#{accountNo}, #{strategy}, #{tradeType}, #{orderType}, #{notes}, #{sourceType}, #{importBatchId}, #{createdAt}, #{updatedAt})")
    void insert(PortfolioTrade trade);

    @Delete("delete from portfolio_trade where trade_id = #{tradeId}")
    int delete(@Param("tradeId") String tradeId);
}
