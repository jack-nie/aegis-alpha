package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.*;
import com.marketmind.alpha.mapper.GovernanceMapper;
import com.marketmind.alpha.mapper.WorkflowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EvidenceServiceTest {
    private GovernanceMapper governanceMapper;
    private WorkflowMapper workflowMapper;
    private ObjectMapper objectMapper;
    private EvidenceService service;

    @BeforeEach
    void setUp() {
        governanceMapper = mock(GovernanceMapper.class);
        workflowMapper = mock(WorkflowMapper.class);
        objectMapper = new ObjectMapper();
        service = new EvidenceService(governanceMapper, workflowMapper, objectMapper);
    }

    @Test
    void evidenceReturnsListFromMapper() {
        when(governanceMapper.findEvidence("run-1")).thenReturn(Collections.emptyList());

        assertThat(service.evidence("run-1")).isEmpty();
        verify(governanceMapper).findEvidence("run-1");
    }

    @Test
    void materializeEvidenceSkipsIfAlreadyExists() {
        when(governanceMapper.countEvidence("run-1")).thenReturn(3);
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");

        service.materializeEvidence(run);

        verify(workflowMapper, never()).findNodeRuns(any());
    }

    @Test
    void materializeEvidenceSkipsIfRunIsNull() {
        service.materializeEvidence(null);
        verifyNoInteractions(governanceMapper, workflowMapper);
    }

    @Test
    void materializeEvidenceInsertsFromSources() throws Exception {
        when(governanceMapper.countEvidence("run-1")).thenReturn(0);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-1");
        nodeRun.setNodeName("news_agent");
        nodeRun.setCompletedAt("2026-01-01 12:00:00");
        String outputJson = objectMapper.writeValueAsString(Map.of(
                "sources", List.of(
                        Map.of("sourceType", "sec-filing", "title", "10-K Annual Report", "url", "https://sec.gov/10k"),
                        Map.of("sourceType", "news", "title", "Market Update", "url", "https://news.example.com")
                )
        ));
        nodeRun.setOutputJson(outputJson);

        when(workflowMapper.findNodeRuns("run-1")).thenReturn(List.of(nodeRun));

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");

        service.materializeEvidence(run);

        verify(governanceMapper, times(2)).insertEvidence(any(EvidenceItem.class));
    }

    @Test
    void materializeEvidenceAssignsTIER1ForFilings() throws Exception {
        when(governanceMapper.countEvidence("run-1")).thenReturn(0);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-1");
        nodeRun.setNodeName("sec_agent");
        nodeRun.setCompletedAt("2026-01-01 12:00:00");
        String outputJson = objectMapper.writeValueAsString(Map.of(
                "sources", List.of(
                        Map.of("sourceType", "sec-filing", "title", "10-K Report")
                )
        ));
        nodeRun.setOutputJson(outputJson);
        when(workflowMapper.findNodeRuns("run-1")).thenReturn(List.of(nodeRun));

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");

        service.materializeEvidence(run);

        verify(governanceMapper).insertEvidence(argThat(item -> "TIER_1".equals(item.getTrustTier())));
    }

    @Test
    void materializeEvidenceAssignsTIER2ForNews() throws Exception {
        when(governanceMapper.countEvidence("run-2")).thenReturn(0);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-2");
        nodeRun.setNodeName("news_agent");
        nodeRun.setCompletedAt("2026-01-01 12:00:00");
        String outputJson = objectMapper.writeValueAsString(Map.of(
                "sources", List.of(
                        Map.of("sourceType", "rss", "title", "Yahoo Finance News")
                )
        ));
        nodeRun.setOutputJson(outputJson);
        when(workflowMapper.findNodeRuns("run-2")).thenReturn(List.of(nodeRun));

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-2");

        service.materializeEvidence(run);

        verify(governanceMapper).insertEvidence(argThat(item -> "TIER_2".equals(item.getTrustTier())));
    }

    @Test
    void materializeEvidenceAssignsTIER3ByDefault() throws Exception {
        when(governanceMapper.countEvidence("run-3")).thenReturn(0);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-3");
        nodeRun.setNodeName("custom_agent");
        nodeRun.setCompletedAt("2026-01-01 12:00:00");
        String outputJson = objectMapper.writeValueAsString(Map.of(
                "sources", List.of(
                        Map.of("sourceType", "internal", "title", "Custom Data")
                )
        ));
        nodeRun.setOutputJson(outputJson);
        when(workflowMapper.findNodeRuns("run-3")).thenReturn(List.of(nodeRun));

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-3");

        service.materializeEvidence(run);

        verify(governanceMapper).insertEvidence(argThat(item -> "TIER_3".equals(item.getTrustTier())));
    }
}