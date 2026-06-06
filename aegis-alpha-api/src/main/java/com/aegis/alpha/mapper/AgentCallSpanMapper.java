package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.AgentCallSpan;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentCallSpanMapper {
    @Insert("insert into agent_call_span(span_id, trace_id, workflow_run_id, node_run_id, backtest_run_id, parent_span_id, span_type, agent_id, tool_name, model_name, status, input_json, output_json, error_message, started_at, completed_at, latency_ms, prompt_tokens, completion_tokens, total_tokens, sort_order) values(#{spanId}, #{traceId}, #{workflowRunId}, #{nodeRunId}, #{backtestRunId}, #{parentSpanId}, #{spanType}, #{agentId}, #{toolName}, #{modelName}, #{status}, #{inputJson}, #{outputJson}, #{errorMessage}, #{startedAt}, #{completedAt}, #{latencyMs}, #{promptTokens}, #{completionTokens}, #{totalTokens}, #{sortOrder})")
    void insert(AgentCallSpan span);

    @Select("select span_id, trace_id, workflow_run_id, node_run_id, backtest_run_id, parent_span_id, span_type, agent_id, tool_name, model_name, status, input_json, output_json, error_message, started_at, completed_at, latency_ms, prompt_tokens, completion_tokens, total_tokens, sort_order from agent_call_span where workflow_run_id = #{workflowRunId} order by sort_order asc")
    List<AgentCallSpan> findByWorkflowRunId(String workflowRunId);
}
