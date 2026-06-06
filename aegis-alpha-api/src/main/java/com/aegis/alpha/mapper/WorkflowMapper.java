package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowLayout;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.domain.WorkflowVersion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkflowMapper {
    @Select("select workflow_key, name, description, engine, version, nodes, edges, readonly_flag, owner_username, updated_at, trigger_keywords, routing_description from workflow_definition order by readonly_flag desc, updated_at desc, nodes asc")
    List<WorkflowDefinition> findDefinitions();

    @Select("select workflow_key, name, description, engine, version, nodes, edges, readonly_flag, owner_username, updated_at, trigger_keywords, routing_description from workflow_definition where workflow_key = #{workflowKey}")
    WorkflowDefinition findDefinition(String workflowKey);

    @Select("select count(*) from workflow_definition")
    int countDefinitions();

    @Insert("insert into workflow_definition(workflow_key, name, description, engine, version, nodes, edges, readonly_flag, owner_username, updated_at, trigger_keywords, routing_description) values(#{workflowKey}, #{name}, coalesce(#{description}, ''),  coalesce(#{engine}, 'langgraph'), #{version}, #{nodes}, #{edges}, #{readonlyFlag}, #{ownerUsername}, coalesce(#{updatedAt}, ''), #{triggerKeywords}, #{routingDescription})")
    void insertDefinition(WorkflowDefinition definition);

    @Update("update workflow_definition set name = #{name}, description = #{description}, engine = #{engine}, version = #{version}, nodes = #{nodes}, edges = #{edges}, readonly_flag = #{readonlyFlag}, owner_username = #{ownerUsername}, updated_at = #{updatedAt}, trigger_keywords = #{triggerKeywords}, routing_description = #{routingDescription} where workflow_key = #{workflowKey}")
    void updateDefinition(WorkflowDefinition definition);

    @Delete("delete from workflow_definition where workflow_key = #{workflowKey} and readonly_flag = 0")
    int deleteEditableDefinition(String workflowKey);

    @Update("update workflow_definition set nodes = #{nodes}, edges = #{edges} where workflow_key = #{workflowKey}")
    void updateDefinitionCounts(@Param("workflowKey") String workflowKey, @Param("nodes") int nodes, @Param("edges") int edges);

    @Update("update workflow_definition set nodes = #{nodes}, edges = #{edges}, updated_at = #{updatedAt} where workflow_key = #{workflowKey}")
    void updateDefinitionCountsWithTime(@Param("workflowKey") String workflowKey, @Param("nodes") int nodes, @Param("edges") int edges, @Param("updatedAt") String updatedAt);

    @Select("select workflow_key, layout_json, updated_at from workflow_layout where workflow_key = #{workflowKey}")
    WorkflowLayout findLayout(String workflowKey);

    @Insert("insert into workflow_layout(workflow_key, layout_json, updated_at) values(#{workflowKey}, #{layoutJson}, #{updatedAt})")
    void insertLayout(WorkflowLayout layout);

    @Update("update workflow_layout set layout_json = #{layoutJson}, updated_at = #{updatedAt} where workflow_key = #{workflowKey}")
    void updateLayout(WorkflowLayout layout);

    @Delete("delete from workflow_layout where workflow_key = #{workflowKey}")
    int deleteLayout(String workflowKey);

    @Select("select version_id, workflow_key, version, layout_json, validation_json, published_by, published_at from workflow_version where workflow_key = #{workflowKey} order by version desc limit 1")
    WorkflowVersion findLatestVersion(String workflowKey);

    @Select("select version_id, workflow_key, version, layout_json, validation_json, published_by, published_at from workflow_version where version_id = #{versionId}")
    WorkflowVersion findVersion(String versionId);

    @Select("select coalesce(max(version), 0) from workflow_version where workflow_key = #{workflowKey}")
    int maxVersion(String workflowKey);

    @Insert("insert into workflow_version(version_id, workflow_key, version, layout_json, validation_json, published_by, published_at) values(#{versionId}, #{workflowKey}, #{version}, #{layoutJson}, #{validationJson}, #{publishedBy}, #{publishedAt})")
    void insertVersion(WorkflowVersion version);

    @Select("select run_id, workflow_key, trace_id, status, subject, started_at, completed_at, result_json, error_message, node_count, idempotency_key, workflow_version_id, inputs_json, control_status, pause_requested, cancel_requested, queued_at from workflow_run order by started_at desc")
    List<WorkflowRun> findRuns();

    @Select("select run_id, workflow_key, trace_id, status, subject, started_at, completed_at, result_json, error_message, node_count, idempotency_key, workflow_version_id, inputs_json, control_status, pause_requested, cancel_requested, queued_at from workflow_run where run_id = #{runId}")
    WorkflowRun findRun(String runId);

    @Select("select run_id, workflow_key, trace_id, status, subject, started_at, completed_at, result_json, error_message, node_count, idempotency_key, workflow_version_id, inputs_json, control_status, pause_requested, cancel_requested, queued_at from workflow_run where workflow_key = #{workflowKey} and coalesce(subject, '') = coalesce(#{subject}, '') and idempotency_key = #{idempotencyKey} order by started_at desc limit 1")
    WorkflowRun findRunByIdempotencyKey(@Param("workflowKey") String workflowKey, @Param("subject") String subject, @Param("idempotencyKey") String idempotencyKey);

    @Select("select run_id, workflow_key, trace_id, status, subject, started_at, completed_at, result_json, error_message, node_count, idempotency_key, workflow_version_id, inputs_json, control_status, pause_requested, cancel_requested, queued_at from workflow_run where status = 'QUEUED' order by queued_at asc, started_at asc limit #{limit}")
    List<WorkflowRun> findQueuedRuns(@Param("limit") int limit);

    @Insert("insert into workflow_run(run_id, workflow_key, trace_id, status, subject, started_at, completed_at, result_json, error_message, node_count, idempotency_key, workflow_version_id, inputs_json, control_status, pause_requested, cancel_requested, queued_at) values(#{runId}, #{workflowKey}, #{traceId}, #{status}, #{subject}, #{startedAt}, #{completedAt}, #{resultJson}, #{errorMessage}, coalesce(#{nodeCount}, 0), #{idempotencyKey}, #{workflowVersionId}, #{inputsJson}, #{controlStatus}, coalesce(#{pauseRequested}, 0), coalesce(#{cancelRequested}, 0), #{queuedAt})")
    void insertRun(WorkflowRun run);

    @Update("update workflow_run set status = #{status}, started_at = #{startedAt}, completed_at = #{completedAt}, result_json = #{resultJson}, error_message = #{errorMessage}, node_count = #{nodeCount}, control_status = #{controlStatus}, pause_requested = coalesce(#{pauseRequested}, 0), cancel_requested = coalesce(#{cancelRequested}, 0) where run_id = #{runId}")
    void updateRun(WorkflowRun run);

    @Insert("insert into workflow_node_run(node_run_id, run_id, node_id, node_name, node_type, agent_id, status, input_json, output_json, error_message, started_at, completed_at, sort_order, attempt, max_attempts, retry_policy_json, timeout_ms) values(#{nodeRunId}, #{runId}, #{nodeId}, #{nodeName}, #{nodeType}, #{agentId}, #{status}, #{inputJson}, #{outputJson}, #{errorMessage}, #{startedAt}, #{completedAt}, #{sortOrder}, coalesce(#{attempt}, 1), coalesce(#{maxAttempts}, 1), #{retryPolicyJson}, #{timeoutMs})")
    void insertNodeRun(WorkflowNodeRun nodeRun);

    @Update("update workflow_node_run set status = #{status}, output_json = #{outputJson}, error_message = #{errorMessage}, completed_at = #{completedAt} where node_run_id = #{nodeRunId}")
    void updateNodeRun(WorkflowNodeRun nodeRun);

    @Select("select node_run_id, run_id, node_id, node_name, node_type, agent_id, status, input_json, output_json, error_message, started_at, completed_at, sort_order, attempt, max_attempts, retry_policy_json, timeout_ms from workflow_node_run where run_id = #{runId} order by sort_order asc")
    List<WorkflowNodeRun> findNodeRuns(String runId);

    @Insert("insert into workflow_run_event(event_id, run_id, event_type, node_run_id, node_id, status, message, payload_json, created_at, sort_order) values(#{eventId}, #{runId}, #{eventType}, #{nodeRunId}, #{nodeId}, #{status}, #{message}, #{payloadJson}, #{createdAt}, #{sortOrder})")
    void insertRunEvent(WorkflowRunEvent event);

    @Select("select event_id, run_id, event_type, node_run_id, node_id, status, message, payload_json, created_at, sort_order from workflow_run_event where run_id = #{runId} order by sort_order asc, created_at asc")
    List<WorkflowRunEvent> findRunEvents(String runId);
}
