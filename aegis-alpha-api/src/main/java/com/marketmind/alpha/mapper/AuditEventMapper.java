package com.marketmind.alpha.mapper;

import com.marketmind.alpha.audit.AuditEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuditEventMapper {
    @Insert("insert into audit_event(event_id, tenant_id, user_id, actor_type, action, resource_type, resource_id, trace_id, request_id, before_json, after_json, ip_address, user_agent, created_at) values(#{eventId}, #{tenantId}, #{userId}, #{actorType}, #{action}, #{resourceType}, #{resourceId}, #{traceId}, #{requestId}, #{beforeJson}, #{afterJson}, #{ipAddress}, #{userAgent}, #{createdAt})")
    void insert(AuditEvent event);

    @Select("select event_id, tenant_id, user_id, actor_type, action, resource_type, resource_id, trace_id, request_id, before_json, after_json, ip_address, user_agent, created_at from audit_event order by created_at desc, event_id desc limit #{limit}")
    List<AuditEvent> findLatest(@Param("limit") int limit);
}
