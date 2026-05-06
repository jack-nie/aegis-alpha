package com.marketmind.alpha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.EvidenceItem;
import com.marketmind.alpha.domain.WorkflowNodeRun;
import com.marketmind.alpha.domain.WorkflowRun;
import com.marketmind.alpha.mapper.GovernanceMapper;
import com.marketmind.alpha.mapper.WorkflowMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EvidenceService {
    private final GovernanceMapper governanceMapper;
    private final WorkflowMapper workflowMapper;
    private final ObjectMapper objectMapper;

    public EvidenceService(GovernanceMapper governanceMapper, WorkflowMapper workflowMapper, ObjectMapper objectMapper) {
        this.governanceMapper = governanceMapper;
        this.workflowMapper = workflowMapper;
        this.objectMapper = objectMapper;
    }

    public List<EvidenceItem> evidence(String workflowRunId) {
        return governanceMapper.findEvidence(workflowRunId);
    }

    public void materializeEvidence(WorkflowRun run) {
        if (run == null || run.getRunId() == null || governanceMapper.countEvidence(run.getRunId()) > 0) {
            return;
        }
        List<WorkflowNodeRun> nodeRuns = workflowMapper.findNodeRuns(run.getRunId());
        for (WorkflowNodeRun nodeRun : nodeRuns) {
            Map<String, Object> output = parse(nodeRun.getOutputJson());
            for (Map<String, Object> source : sources(output)) {
                EvidenceItem item = new EvidenceItem();
                item.setEvidenceId(UUID.randomUUID().toString());
                item.setWorkflowRunId(run.getRunId());
                item.setNodeRunId(nodeRun.getNodeRunId());
                item.setSourceType(text(first(source.get("sourceType"), source.get("type")), "workflow"));
                item.setTitle(text(source.get("title"), nodeRun.getNodeName()));
                item.setUrl(text(source.get("url"), ""));
                item.setTrustTier(trustTier(item.getSourceType(), item.getTitle()));
                item.setSummary(text(first(source.get("summary"), output.get("summary")), ""));
                item.setRetrievedAt(text(first(source.get("retrievedAt"), source.get("publishedAt")), text(nodeRun.getCompletedAt(), now())));
                governanceMapper.insertEvidence(item);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sources(Map<String, Object> output) {
        Object sources = output.get("sources");
        if (sources instanceof List) {
            return (List<Map<String, Object>>) sources;
        }
        return java.util.Collections.emptyList();
    }

    private String trustTier(String sourceType, String title) {
        String combined = (sourceType + " " + title).toLowerCase();
        if (combined.contains("filing") || combined.contains("sec") || combined.contains("10-k") || combined.contains("10-q")) {
            return "TIER_1";
        }
        if (combined.contains("news") || combined.contains("rss") || combined.contains("yahoo") || combined.contains("press")) {
            return "TIER_2";
        }
        return "TIER_3";
    }

    private Object first(Object value, Object fallback) {
        return value == null || String.valueOf(value).trim().isEmpty() ? fallback : value;
    }

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
