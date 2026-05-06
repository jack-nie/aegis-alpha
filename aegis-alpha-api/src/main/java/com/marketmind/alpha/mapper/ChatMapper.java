package com.marketmind.alpha.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
