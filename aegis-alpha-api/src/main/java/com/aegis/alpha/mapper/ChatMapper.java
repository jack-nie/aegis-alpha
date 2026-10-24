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

    /** Claims a legacy (unowned) thread; returns 0 when another caller already owns it (lost race). */
    @Update("update chat_thread set user_id = #{userId}, tenant_id = #{tenantId} where thread_id = #{threadId}"
            + " and (user_id is null or user_id = '') and (tenant_id is null or tenant_id = '')")
    int updateThreadOwner(@Param("threadId") String threadId,
                           @Param("userId") String userId,
                           @Param("tenantId") String tenantId);

    @Select("select thread_id, title from chat_thread where user_id = #{userId} and tenant_id = #{tenantId} order by thread_id")
    List<Map<String, Object>> threadsForPrincipal(@Param("userId") String userId,
                                                  @Param("tenantId") String tenantId);

    /** P4-11/P4-12: newest messages of a thread (callers reverse to chronological order). */
    @Select("select message_id, role, content, created_at from chat_message where thread_id = #{threadId}"
            + " order by created_at desc, message_id desc limit #{limit}")
    List<Map<String, Object>> findRecentMessages(@Param("threadId") String threadId, @Param("limit") int limit);
}
