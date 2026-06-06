package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.EvidenceItem;
import com.aegis.alpha.domain.LlmCall;
import com.aegis.alpha.domain.ModelConfig;
import com.aegis.alpha.domain.Recommendation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface GovernanceMapper {
    @Select("select count(*) from model_config")
    int countModelConfigs();

    @Insert("insert into model_config(model_config_id, provider, model_name, status, context_window, prompt_token_cost_usd, completion_token_cost_usd, fallback_model, created_at) values(#{modelConfigId}, #{provider}, #{modelName}, #{status}, #{contextWindow}, #{promptTokenCostUsd}, #{completionTokenCostUsd}, #{fallbackModel}, #{createdAt})")
    void insertModelConfig(ModelConfig modelConfig);

    @Select("select model_config_id, provider, model_name, status, context_window, prompt_token_cost_usd, completion_token_cost_usd, fallback_model, created_at from model_config order by status asc, provider asc, model_name asc")
    List<ModelConfig> findModelConfigs();

    @Select("select model_config_id, provider, model_name, status, context_window, prompt_token_cost_usd, completion_token_cost_usd, fallback_model, created_at from model_config where provider = #{provider} and model_name = #{modelName} order by created_at desc limit 1")
    ModelConfig findModelConfig(@Param("provider") String provider, @Param("modelName") String modelName);

    @Select("select count(*) from llm_call where workflow_run_id = #{workflowRunId}")
    int countLlmCalls(String workflowRunId);

    @Insert("insert into llm_call(llm_call_id, workflow_run_id, node_run_id, trace_id, provider, model_name, status, prompt_tokens, completion_tokens, total_tokens, estimated_cost_usd, started_at, completed_at) values(#{llmCallId}, #{workflowRunId}, #{nodeRunId}, #{traceId}, #{provider}, #{modelName}, #{status}, #{promptTokens}, #{completionTokens}, #{totalTokens}, #{estimatedCostUsd}, #{startedAt}, #{completedAt})")
    void insertLlmCall(LlmCall call);

    @Select("select llm_call_id, workflow_run_id, node_run_id, trace_id, provider, model_name, status, prompt_tokens, completion_tokens, total_tokens, estimated_cost_usd, started_at, completed_at from llm_call where workflow_run_id = #{workflowRunId} order by started_at asc, llm_call_id asc")
    List<LlmCall> findLlmCalls(String workflowRunId);

    @Select("select count(*) from evidence_item where workflow_run_id = #{workflowRunId}")
    int countEvidence(String workflowRunId);

    @Insert("insert into evidence_item(evidence_id, workflow_run_id, node_run_id, source_type, title, url, trust_tier, summary, retrieved_at) values(#{evidenceId}, #{workflowRunId}, #{nodeRunId}, #{sourceType}, #{title}, #{url}, #{trustTier}, #{summary}, #{retrievedAt})")
    void insertEvidence(EvidenceItem item);

    @Select("select evidence_id, workflow_run_id, node_run_id, source_type, title, url, trust_tier, summary, retrieved_at from evidence_item where workflow_run_id = #{workflowRunId} order by retrieved_at asc, evidence_id asc")
    List<EvidenceItem> findEvidence(String workflowRunId);

    @Select("select recommendation_id, workflow_run_id, backtest_run_id, trace_id, symbol, recommendation, confidence, time_horizon, rationale_json, risk_json, missing_data_json, disclaimer, approval_status, created_at from recommendation order by created_at desc, recommendation_id desc")
    List<Recommendation> findRecommendations();

    @Select("select recommendation_id, workflow_run_id, backtest_run_id, trace_id, symbol, recommendation, confidence, time_horizon, rationale_json, risk_json, missing_data_json, disclaimer, approval_status, created_at from recommendation where workflow_run_id = #{workflowRunId}")
    Recommendation findRecommendation(String workflowRunId);

    @Insert("insert into recommendation(recommendation_id, workflow_run_id, backtest_run_id, trace_id, symbol, recommendation, confidence, time_horizon, rationale_json, risk_json, missing_data_json, disclaimer, approval_status, created_at) values(#{recommendationId}, #{workflowRunId}, #{backtestRunId}, #{traceId}, #{symbol}, #{recommendation}, #{confidence}, #{timeHorizon}, #{rationaleJson}, #{riskJson}, #{missingDataJson}, #{disclaimer}, #{approvalStatus}, #{createdAt})")
    void insertRecommendation(Recommendation recommendation);

    @Update("update recommendation set approval_status = #{approvalStatus} where workflow_run_id = #{workflowRunId}")
    void updateRecommendationApproval(@Param("workflowRunId") String workflowRunId, @Param("approvalStatus") String approvalStatus);
}
