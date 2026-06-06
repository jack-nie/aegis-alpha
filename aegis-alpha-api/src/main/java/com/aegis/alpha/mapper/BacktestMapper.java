package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.BacktestRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BacktestMapper {
    @Select("select id, run_name, strategy, status, total_return_pct, sharpe, started_at, completed_at, workflow_run_id, trace_id, subject, symbol, inputs_json, result_json, error_message, node_count, final_recommendation, confidence from backtest_run order by started_at desc, run_name desc, id desc")
    List<BacktestRun> findAll();

    @Select("select id, run_name, strategy, status, total_return_pct, sharpe, started_at, completed_at, workflow_run_id, trace_id, subject, symbol, inputs_json, result_json, error_message, node_count, final_recommendation, confidence from backtest_run where workflow_run_id = #{workflowRunId}")
    BacktestRun findByWorkflowRunId(String workflowRunId);

    @Insert("insert into backtest_run(id, run_name, strategy, status, total_return_pct, sharpe, started_at, completed_at, workflow_run_id, trace_id, subject, symbol, inputs_json, result_json, error_message, node_count, final_recommendation, confidence) values(#{id}, #{runName}, #{strategy}, #{status}, #{totalReturnPct}, #{sharpe}, #{startedAt}, #{completedAt}, #{workflowRunId}, #{traceId}, #{subject}, #{symbol}, #{inputsJson}, #{resultJson}, #{errorMessage}, #{nodeCount}, #{finalRecommendation}, #{confidence})")
    void insert(BacktestRun run);
}
