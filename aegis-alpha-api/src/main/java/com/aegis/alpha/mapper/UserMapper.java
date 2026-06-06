package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("select user_id, username, password_hash, tenant_id, roles from mm_user where username = #{username}")
    User findByUsername(String username);

    @Select("select count(*) from mm_user")
    int count();

    @Insert("insert into mm_user(user_id, username, password_hash, tenant_id, roles) values(#{userId}, #{username}, #{passwordHash}, #{tenantId}, #{roles})")
    void insert(User user);
}
