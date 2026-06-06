package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.BacktestRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.BacktestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BacktestServiceTest {
    private BacktestMapper mapper;
    private ObjectMapper objectMapper;
    private ModelGovernanceService modelGovernanceService;
    private EvidenceService evidenceService;
    private RecommendationService recommendationService;
    private BacktestService service;

    @BeforeEach
    void setUp() {
        mapper = mock(BacktestMapper.class);
        objectMapper = new ObjectMapper();
        modelGovernanceService = mock(ModelGovernanceService.class);
        evidenceService = mock(EvidenceService.class);
        recommendationService = mock(RecommendationService.class);
        service = new BacktestService(mapper, objectMapper, modelGovernanceService, evidenceService, recommendationService);
    }

    @Test
    void findAllDelegatesToMapper() {
        when(mapper.findAll()).thenReturn(java.util.Collections.emptyList());
        assertThat(service.findAll()).isEmpty();
    }

    @Test
    void createWithDefaults() {
        BacktestRun result = service.create("Test Run", "Momentum");

        assertThat(result.getRunName()).isEqualTo("Test Run");
        assertThat(result.getStrategy()).isEqualTo("Momentum");
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getTotalReturnPct()).isEqualByComparingTo(new BigDecimal("8.40"));
        assertThat(result.getSharpe()).isEqualByComparingTo(new BigDecimal("1.24"));
        verify(mapper).insert(any(BacktestRun.class));
    }

    @Test
    void createWithNullsUsesDefaults() {
        BacktestRun result = service.create(null, null);

        assertThat(result.getRunName()).isEqualTo("New Backtest");
        assertThat(result.getStrategy()).isEqualTo("Quality Value");
    }

    @Test
    void createFromWorkflowRunThrowsIfNull() {
        assertThatThrownBy(() -> service.createFromWorkflowRun(null, new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowRun is required");
    }

    @Test
    void createFromWorkflowRunReturnsExistingIfFound() {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setRunId("run-1");
        workflowRun.setWorkflowKey("deep_dive");

        BacktestRun existing = new BacktestRun();
        existing.setId("bt-existing");
        when(mapper.findByWorkflowRunId("run-1")).thenReturn(existing);

        BacktestRun result = service.createFromWorkflowRun(workflowRun, new HashMap<>());

        assertThat(result).isSameAs(existing);
        verify(mapper, never()).insert(any());
        verify(modelGovernanceService).materializeCalls(workflowRun);
        verify(evidenceService).materializeEvidence(workflowRun);
    }

    @Test
    void createFromWorkflowRunExtractsSymbolFromInputs() {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setRunId("run-2");
        workflowRun.setWorkflowKey("deep_dive");
        workflowRun.setStatus("COMPLETED");
        workflowRun.setSubject("Analyze NVDA");
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", "NVDA");

        when(mapper.findByWorkflowRunId("run-2")).thenReturn(null);
        when(recommendationService.createFromWorkflowRun(any(), any(), any())).thenReturn(null);

        BacktestRun result = service.createFromWorkflowRun(workflowRun, inputs);

        assertThat(result.getSymbol()).isEqualTo("NVDA");
        verify(mapper).insert(any(BacktestRun.class));
    }

    @Test
    void createFromWorkflowRunUsesSubjectAsName() {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setRunId("run-3");
        workflowRun.setWorkflowKey("deep_dive");
        workflowRun.setStatus("COMPLETED");
        workflowRun.setSubject("My Analysis");

        when(mapper.findByWorkflowRunId("run-3")).thenReturn(null);
        when(recommendationService.createFromWorkflowRun(any(), any(), any())).thenReturn(null);

        BacktestRun result = service.createFromWorkflowRun(workflowRun, null);

        assertThat(result.getRunName()).isEqualTo("My Analysis");
    }

    @Test
    void createFromWorkflowRunExtractsRecommendationFromResultJson() throws Exception {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setRunId("run-4");
        workflowRun.setWorkflowKey("deep_dive");
        workflowRun.setStatus("COMPLETED");
        workflowRun.setResultJson(objectMapper.writeValueAsString(Map.of(
                "stock_recommendation", Map.of("summary", "Strong BUY signal", "confidence", 0.9)
        )));

        when(mapper.findByWorkflowRunId("run-4")).thenReturn(null);
        when(recommendationService.createFromWorkflowRun(any(), any(), any())).thenReturn(null);

        BacktestRun result = service.createFromWorkflowRun(workflowRun, null);

        assertThat(result.getFinalRecommendation()).isEqualTo("Strong BUY signal");
        assertThat(result.getConfidence()).isEqualByComparingTo(new BigDecimal("0.9"));
    }
}