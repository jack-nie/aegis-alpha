package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.MarketIndicator;
import com.aegis.alpha.domain.MarketSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface DashboardMapper {
    @Select("select as_of_date as row_date, china, usa, europe, japan from macro_quadrant order by as_of_date")
    List<Map<String, Object>> quadrantRows();

    @Select("select period, china, usa, europe, japan from credit_impulse order by period")
    List<Map<String, Object>> creditRows();

    @Select("select name, value, subtitle from market_indicator order by name")
    List<MarketIndicator> indicators();

    @Select("select name, symbol, change_pct from market_snapshot order by name")
    List<MarketSnapshot> markets();

    @Select("select count(*) from macro_quadrant")
    int countQuadrant();

    @Insert("insert into macro_quadrant(as_of_date, china, usa, europe, japan) values(#{date}, #{china}, #{usa}, #{europe}, #{japan})")
    void insertQuadrant(@Param("date") String date, @Param("china") int china, @Param("usa") int usa, @Param("europe") int europe, @Param("japan") int japan);

    @Insert("insert into credit_impulse(period, china, usa, europe, japan) values(#{period}, #{china}, #{usa}, #{europe}, #{japan})")
    void insertCredit(@Param("period") String period, @Param("china") String china, @Param("usa") String usa, @Param("europe") String europe, @Param("japan") String japan);

    @Insert("insert into market_indicator(name, value, subtitle) values(#{name}, #{value}, #{subtitle})")
    void insertIndicator(@Param("name") String name, @Param("value") String value, @Param("subtitle") String subtitle);

    @Insert("insert into market_snapshot(symbol, name, change_pct) values(#{symbol}, #{name}, #{changePct})")
    void insertMarket(@Param("symbol") String symbol, @Param("name") String name, @Param("changePct") java.math.BigDecimal changePct);
}
