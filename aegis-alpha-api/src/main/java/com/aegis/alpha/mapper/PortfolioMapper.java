package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.Portfolio;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PortfolioMapper {
    @Select("select id, name, nav, return_pct, assets, transactions, option_combos, updated_at from portfolio order by updated_at desc")
    List<Portfolio> findAll();

    @Select("select id, name, nav, return_pct, assets, transactions, option_combos, updated_at from portfolio where id = #{id}")
    Portfolio findById(String id);

    @Select("select count(*) from portfolio")
    int count();

    @Insert("insert into portfolio(id, name, nav, return_pct, assets, transactions, option_combos, updated_at) values(#{id}, #{name}, #{nav}, #{returnPct}, #{assets}, #{transactions}, #{optionCombos}, #{updatedAt})")
    void insert(Portfolio portfolio);
}
