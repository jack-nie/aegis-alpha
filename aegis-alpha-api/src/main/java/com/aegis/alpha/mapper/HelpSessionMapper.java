package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.HelpSessionState;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis mapper for {@code help_thread_state} (per-thread Help session goal + checkpoint metadata).
 * Snake_case columns map to camelCase properties via {@code mybatis.configuration.map-underscore-to-camel-case}.
 */
@Mapper
public interface HelpSessionMapper {

    @Select("select thread_id, user_id, tenant_id, goal, last_run_id, last_domain, last_route_action, pending_clarification, checkpoint_json, updated_at from help_thread_state where thread_id = #{threadId}")
    HelpSessionState find(@Param("threadId") String threadId);

    @Insert("insert into help_thread_state(thread_id, user_id, tenant_id, goal, last_run_id, last_domain, last_route_action, pending_clarification, checkpoint_json, updated_at) values(#{threadId}, #{userId}, #{tenantId}, #{goal}, #{lastRunId}, #{lastDomain}, #{lastRouteAction}, #{pendingClarification}, #{checkpointJson}, #{updatedAt}) on duplicate key update user_id=values(user_id), tenant_id=values(tenant_id), goal=values(goal), last_run_id=values(last_run_id), last_domain=values(last_domain), last_route_action=values(last_route_action), pending_clarification=values(pending_clarification), checkpoint_json=values(checkpoint_json), updated_at=values(updated_at)")
    int upsert(HelpSessionState state);

    @Delete("delete from help_thread_state where thread_id = #{threadId}")
    int delete(@Param("threadId") String threadId);
}
