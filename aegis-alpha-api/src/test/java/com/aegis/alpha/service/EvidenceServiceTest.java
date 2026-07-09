package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.alpha.domain.*;
import com.aegis.alpha.mapper.GovernanceMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.AbstractMap.SimpleEntry;

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

    @SafeVarargs
    private static <K, V> Map<K, V> mapOf(Map.Entry<K, V>... entries) {
        Map<K, V> map = new HashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    private static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return new SimpleEntry<>(key, value);
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
        String outputJson = objectMapper.writeValueAsString(mapOf(
                entry("sources", Arrays.asList(
                        mapOf(entry("sourceType", "sec-filing"), entry("title", "10-K Annual Report"), entry("url", "https://sec.gov/10k")),
                        mapOf(entry("sourceType", "news"), entry("title", "Market Update"), entry("url", "https://news.example.com"))
                ))
        ));
        nodeRun.setOutputJson(outputJson);

        when(workflowMapper.findNodeRuns("run-1")).thenReturn(Arrays.asList(nodeRun));

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
        String outputJson = objectMapper.writeValueAsString(mapOf(
                entry("sources", Arrays.asList(
                        mapOf(entry("sourceType", "sec-filing"), entry("title", "10-K Report"))
                ))
        ));
        nodeRun.setOutputJson(outputJson);
        when(workflowMapper.findNodeRuns("run-1")).thenReturn(Arrays.asList(nodeRun));

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
        String outputJson = objectMapper.writeValueAsString(mapOf(
                entry("sources", Arrays.asList(
                        mapOf(entry("sourceType", "rss"), entry("title", "Yahoo Finance News"))
                ))
        ));
        nodeRun.setOutputJson(outputJson);
        when(workflowMapper.findNodeRuns("run-2")).thenReturn(Arrays.asList(nodeRun));

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
        String outputJson = objectMapper.writeValueAsString(mapOf(
                entry("sources", Arrays.asList(
                        mapOf(entry("sourceType", "internal"), entry("title", "Custom Data"))
                ))
        ));
        nodeRun.setOutputJson(outputJson);
        when(workflowMapper.findNodeRuns("run-3")).thenReturn(Arrays.asList(nodeRun));

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-3");

        service.materializeEvidence(run);

        verify(governanceMapper).insertEvidence(argThat(item -> "TIER_3".equals(item.getTrustTier())));
    }

    @Test
    void materializeEvidenceInsertsFromDataClaims() throws Exception {
        when(governanceMapper.countEvidence("run-claims")).thenReturn(0);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-claims");
        nodeRun.setNodeName("aggregate");
        nodeRun.setCompletedAt("2026-01-01 12:00:00");
        String outputJson = objectMapper.writeValueAsString(mapOf(
                entry("data", mapOf(
                        entry("claims", Arrays.asList(
                                mapOf(
                                        entry("claimId", "c_last_price"),
                                        entry("field", "last_price"),
                                        entry("value", 190.2),
                                        entry("evidenceId", "e-market-1"),
                                        entry("asOf", "2026-01-01T15:30:00Z")
                                ),
                                mapOf(
                                        entry("claimId", "c_thesis"),
                                        entry("field", "thesis_summary"),
                                        entry("value", "growth story"),
                                        entry("asOf", "2026-01-01T15:30:00Z")
                                )
                        ))
                ))
        ));
        nodeRun.setOutputJson(outputJson);
        when(workflowMapper.findNodeRuns("run-claims")).thenReturn(Arrays.asList(nodeRun));

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-claims");

        service.materializeEvidence(run);

        verify(governanceMapper).insertEvidence(argThat(item ->
                "market".equals(item.getSourceType())
                        && "TIER_1".equals(item.getTrustTier())
                        && "e-market-1".equals(item.getEvidenceId())
                        && item.getTitle().contains("last_price")
                        && item.getTitle().contains("190.2")
        ));
        verify(governanceMapper).insertEvidence(argThat(item ->
                "claim".equals(item.getSourceType())
                        && "TIER_3".equals(item.getTrustTier())
                        && item.getTitle().contains("thesis_summary")
        ));
        verify(governanceMapper, times(2)).insertEvidence(any(EvidenceItem.class));
    }

    @Test
    void materializeEvidenceKeepsSourcesAndClaimsTogether() throws Exception {
        when(governanceMapper.countEvidence("run-both")).thenReturn(0);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setNodeRunId("nr-both");
        nodeRun.setNodeName("research");
        nodeRun.setCompletedAt("2026-01-01 12:00:00");
        String outputJson = objectMapper.writeValueAsString(mapOf(
                entry("sources", Arrays.asList(
                        mapOf(entry("sourceType", "news"), entry("title", "Headline"))
                )),
                entry("data", mapOf(
                        entry("claims", Arrays.asList(
                                mapOf(entry("field", "revenue"), entry("value", 1000), entry("claimId", "c_rev"))
                        ))
                ))
        ));
        nodeRun.setOutputJson(outputJson);
        when(workflowMapper.findNodeRuns("run-both")).thenReturn(Arrays.asList(nodeRun));

        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-both");

        service.materializeEvidence(run);

        verify(governanceMapper, times(2)).insertEvidence(any(EvidenceItem.class));
    }
}