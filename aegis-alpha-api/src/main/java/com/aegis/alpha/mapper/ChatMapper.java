package com.aegis.alpha.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface ChatMapper {
    @Select("select thread_id, title from chat_thread order by thread_id")
    List<Map<String, Object>> threads();

    @Insert("insert into chat_thread(thread_id, title) values(#{threadId}, #{title})")
    void insertThread(@Param("threadId") String threadId, @Param("title") String title);

    @Insert("insert into chat_message(message_id, thread_id, role, content) values(#{messageId}, #{threadId}, #{role}, #{content})")
    void insertMessage(@Param("messageId") String messageId, @Param("threadId") String threadId, @Param("role") String role, @Param("content") String content);

    /* ================================================================ *
     * Thread ownership (P0): a thread belongs to one user + tenant.
     * Legacy rows created before ownership tracking have null owners and
     * are claimed by the first authenticated user that continues them.
     * ================================================================ */

    @Select("select user_id, tenant_id from chat_thread where thread_id = #{threadId}")
    Map<String, Object> findThreadOwner(@Param("threadId") String threadId);

    @Insert("insert into chat_thread(thread_id, title, user_id, tenant_id) values(#{threadId}, #{title}, #{userId}, #{tenantId})")
    void insertThreadOwned(@Param("threadId") String threadId,
                           @Param("title") String title,
                           @Param("userId") String userId,
                           @Param("tenantId") String tenantId);

    @Update("update chat_thread set user_id = #{userId}, tenant_id = #{tenantId} where thread_id = #{threadId}")
    void updateThreadOwner(@Param("threadId") String threadId,
                           @Param("userId") String userId,
                           @Param("tenantId") String tenantId);

    @Select("select thread_id, title from chat_thread where user_id = #{userId} and tenant_id = #{tenantId} order by thread_id")
    List<Map<String, Object>> threadsForPrincipal(@Param("userId") String userId,
                                                  @Param("tenantId") String tenantId);
}
