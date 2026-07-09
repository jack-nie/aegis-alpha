package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.BacktestRun;
import com.aegis.alpha.domain.Recommendation;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.GovernanceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 0.5 approve-gate unit tests: empty evidence forces degraded/INSUFFICIENT_DATA
 * and approve() must refuse those drafts.
 */
class RecommendationServiceApproveTest {
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
    void createFromWorkflowRunWithEmptyEvidenceForcesInsufficientOrDegraded() {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setRunId("run-approve-1");
        workflowRun.setStatus("COMPLETED");
        workflowRun.setResultJson(
                "{\"stock_recommendation\":{\"summary\":\"Strong BUY signal\",\"confidence\":0.85,\"recommendation\":\"BUY\"}}");

        BacktestRun backtestRun = new BacktestRun();
        backtestRun.setId("bt-approve-1");
        backtestRun.setSymbol("AAPL");
        backtestRun.setConfidence(new BigDecimal("0.85"));

        when(governanceMapper.findRecommendation("run-approve-1")).thenReturn(null);
        when(evidenceService.evidence("run-approve-1")).thenReturn(Collections.emptyList());

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "AAPL");

        Recommendation result = service.createFromWorkflowRun(workflowRun, backtestRun, inputs);

        assertThat(result).isNotNull();
        boolean insufficient = "INSUFFICIENT_DATA".equals(result.getRecommendation());
        boolean degradedDisclaimer = result.getDisclaimer() != null
                && result.getDisclaimer().contains("DEGRADED");
        assertThat(insufficient || degradedDisclaimer)
                .as("empty evidence must force INSUFFICIENT_DATA and/or degraded disclaimer")
                .isTrue();
        assertThat(result.getApprovalStatus()).isEqualTo("PENDING_REVIEW");
        verify(governanceMapper).insertRecommendation(any(Recommendation.class));
    }

    @Test
    void approveThrowsWhenRecommendationIsInsufficientData() {
        Recommendation rec = new Recommendation();
        rec.setRecommendationId("r-insuff");
        rec.setWorkflowRunId("run-insuff");
        rec.setRecommendation("INSUFFICIENT_DATA");
        rec.setApprovalStatus("PENDING_REVIEW");
        rec.setDisclaimer("This AI-generated recommendation is not investment advice.");
        rec.setRationaleJson("{\"degraded\":false}");

        when(governanceMapper.findRecommendation("run-insuff")).thenReturn(rec);
        when(evidenceService.evidence("run-insuff")).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.approve("run-insuff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not approvable");
    }

    @Test
    void approveThrowsWhenRecommendationIsDegraded() {
        Recommendation rec = new Recommendation();
        rec.setRecommendationId("r-degraded");
        rec.setWorkflowRunId("run-degraded");
        rec.setRecommendation("HOLD");
        rec.setApprovalStatus("PENDING_REVIEW");
        rec.setDisclaimer("[DRAFT/DEGRADED — not approvable until re-run with complete evidence] Not advice.");
        rec.setRationaleJson("{\"degraded\":true}");

        when(governanceMapper.findRecommendation("run-degraded")).thenReturn(rec);
        when(evidenceService.evidence("run-degraded")).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.approve("run-degraded"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not approvable");
    }

    @Test
    void isApprovableFalseForInsufficientData() {
        Recommendation rec = new Recommendation();
        rec.setRecommendation("INSUFFICIENT_DATA");
        rec.setApprovalStatus("PENDING_REVIEW");

        assertThat(service.isApprovable(rec)).isFalse();
    }
}
