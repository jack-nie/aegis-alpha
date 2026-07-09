package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.*;
import com.aegis.alpha.mapper.GovernanceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecommendationServiceTest {
    private GovernanceMapper governanceMapper;
    private EvidenceService evidenceService;
    private ObjectMapper objectMapper;
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        governanceMapper = mock(GovernanceMapper.class);
        evidenceService = mock(EvidenceService.class);
        objectMapper = new ObjectMapper();
        service = new RecommendationService(governanceMapper, evidenceService, objectMapper);
    }

    @Test
    void recommendationsDelegatesToMapper() {
        when(governanceMapper.findRecommendations()).thenReturn(Collections.emptyList());
        assertThat(service.recommendations()).isEmpty();
    }

    @Test
    void detailReturnsNullWhenNotFound() {
        when(governanceMapper.findRecommendation("missing")).thenReturn(null);

        assertThat(service.detail("missing")).isNull();
    }

    @Test
    void detailReturnsRecommendationWithEvidence() {
        Recommendation rec = new Recommendation();
        rec.setRecommendationId("r-1");
        when(governanceMapper.findRecommendation("run-1")).thenReturn(rec);
        when(evidenceService.evidence("run-1")).thenReturn(Collections.emptyList());

        Map<String, Object> detail = service.detail("run-1");

        assertThat(detail).containsKey("recommendation");
        assertThat(detail).containsKey("evidence");
        assertThat(detail).containsKey("approvable");
        assertThat(detail.get("approvable")).isInstanceOf(Boolean.class);
    }

    @Test
    void createFromWorkflowRunReturnsNullIfWorkflowIsNull() {
        assertThat(service.createFromWorkflowRun(null, new BacktestRun(), new HashMap<>())).isNull();
    }

    @Test
    void createFromWorkflowRunReturnsNullIfBacktestIsNull() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");
        assertThat(service.createFromWorkflowRun(run, null, new HashMap<>())).isNull();
    }

    @Test
    void createFromWorkflowRunReturnsExistingIfAlreadyPresent() {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setRunId("run-1");
        workflowRun.setStatus("COMPLETED");
        workflowRun.setResultJson("{\"summary\":\"BUY AAPL\"}");

        BacktestRun backtestRun = new BacktestRun();
        backtestRun.setId("bt-1");
        backtestRun.setConfidence(BigDecimal.ZERO);

        Recommendation existing = new Recommendation();
        existing.setRecommendationId("existing-r");
        when(governanceMapper.findRecommendation("run-1")).thenReturn(existing);

        Recommendation result = service.createFromWorkflowRun(workflowRun, backtestRun, new HashMap<>());

        assertThat(result).isSameAs(existing);
        verify(governanceMapper, never()).insertRecommendation(any());
    }

    @Test
    void createFromWorkflowRunCreatesNewLabel() {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setRunId("run-1");
        workflowRun.setStatus("COMPLETED");
        workflowRun.setResultJson("{\"stock_recommendation\":{\"summary\":\"Strong BUY signal\",\"confidence\":0.85}}");

        BacktestRun backtestRun = new BacktestRun();
        backtestRun.setId("bt-1");
        backtestRun.setSymbol("AAPL");
        backtestRun.setConfidence(new BigDecimal("0.85"));

        when(governanceMapper.findRecommendation("run-1")).thenReturn(null);
        when(evidenceService.evidence("run-1")).thenReturn(Collections.emptyList());

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "AAPL");

        Recommendation result = service.createFromWorkflowRun(workflowRun, backtestRun, inputs);

        // Empty evidence forces actionable BUY → INSUFFICIENT_DATA (approve gate)
        assertThat(result.getRecommendation()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.getSymbol()).isEqualTo("AAPL");
        assertThat(result.getApprovalStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(result.getDisclaimer()).contains("DEGRADED");
        verify(governanceMapper).insertRecommendation(any(Recommendation.class));
    }

    @Test
    void createFromWorkflowRunLabelSell() {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setRunId("run-2");
        workflowRun.setStatus("COMPLETED");
        workflowRun.setResultJson("{\"stock_recommendation\":{\"summary\":\"SELL this stock now\"}}");

        BacktestRun backtestRun = new BacktestRun();
        backtestRun.setId("bt-2");
        backtestRun.setConfidence(BigDecimal.ZERO);

        when(governanceMapper.findRecommendation("run-2")).thenReturn(null);
        when(evidenceService.evidence("run-2")).thenReturn(Collections.emptyList());

        Recommendation result = service.createFromWorkflowRun(workflowRun, backtestRun, null);

        // Empty evidence forces actionable SELL → INSUFFICIENT_DATA (approve gate)
        assertThat(result.getRecommendation()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void createFromWorkflowRunLabelInsufficientDataIfNotCompleted() {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setRunId("run-3");
        workflowRun.setStatus("FAILED");
        workflowRun.setResultJson("{}");

        BacktestRun backtestRun = new BacktestRun();
        backtestRun.setId("bt-3");
        backtestRun.setConfidence(BigDecimal.ZERO);

        when(governanceMapper.findRecommendation("run-3")).thenReturn(null);
        when(evidenceService.evidence("run-3")).thenReturn(Collections.emptyList());

        Recommendation result = service.createFromWorkflowRun(workflowRun, backtestRun, null);

        assertThat(result.getRecommendation()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void approveUpdatesStatus() {
        Recommendation rec = new Recommendation();
        rec.setRecommendationId("r-1");
        when(governanceMapper.findRecommendation("run-1")).thenReturn(rec);

        service.approve("run-1");

        verify(governanceMapper).updateRecommendationApproval("run-1", "APPROVED");
    }

    @Test
    void rejectUpdatesStatus() {
        Recommendation rec = new Recommendation();
        rec.setRecommendationId("r-1");
        when(governanceMapper.findRecommendation("run-1")).thenReturn(rec);

        service.reject("run-1");

        verify(governanceMapper).updateRecommendationApproval("run-1", "REJECTED");
    }

    @Test
    void approveReturnsNullIfNotFound() {
        when(governanceMapper.findRecommendation("missing")).thenReturn(null);
        assertThat(service.approve("missing")).isNull();
    }

    @Test
    void rejectReturnsNullIfNotFound() {
        when(governanceMapper.findRecommendation("missing")).thenReturn(null);
        assertThat(service.reject("missing")).isNull();
    }
}