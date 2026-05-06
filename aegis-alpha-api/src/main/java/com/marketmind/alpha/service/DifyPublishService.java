package com.marketmind.alpha.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DifyPublishService {
    private final RestTemplate restTemplate = new RestTemplate();
    private final DifyDslService dslService;
    private final String consoleBaseUrl;
    private final String consoleToken;

    public DifyPublishService(DifyDslService dslService,
                              @Value("${marketmind.dify.console-base-url:}") String consoleBaseUrl,
                              @Value("${marketmind.dify.console-token:}") String consoleToken) {
        this.dslService = dslService;
        this.consoleBaseUrl = trimRight(consoleBaseUrl);
        this.consoleToken = consoleToken;
    }

    public Map<String, Object> exportDsl(String workflowKey) {
        return dslService.export(workflowKey);
    }

    public Map<String, Object> publish(String workflowKey) {
        Map<String, Object> exported = dslService.export(workflowKey);
        Map<String, Object> result = new LinkedHashMap<String, Object>(exported);
        if (isEmpty(consoleBaseUrl) || isEmpty(consoleToken)) {
            result.put("published", Boolean.FALSE);
            result.put("reason", "Dify Console URL or token is not configured. YAML was generated but not imported.");
            return result;
        }

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("mode", "yaml-content");
        body.put("import_mode", "yaml-content");
        body.put("yaml_content", exported.get("yaml"));
        body.put("name", "Aegis Alpha " + workflowKey);

        try {
            Map<String, Object> importResponse = post("/console/api/apps/imports", body);
            result.put("published", Boolean.TRUE);
            result.put("importResponse", importResponse);
            Object appId = firstValue(importResponse, "app_id", "appId", "id");
            if (appId != null) {
                result.put("appId", appId);
                result.put("publishResponse", tryPublishDraft(String.valueOf(appId)));
            }
            return result;
        } catch (Exception ex) {
            result.put("published", Boolean.FALSE);
            result.put("reason", ex.getMessage());
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + consoleToken);
        return restTemplate.postForObject(consoleBaseUrl + path, new HttpEntity<Map<String, Object>>(body, headers), Map.class);
    }

    private Map<String, Object> tryPublishDraft(String appId) {
        Map<String, Object> empty = new LinkedHashMap<String, Object>();
        try {
            return post("/console/api/apps/" + appId + "/workflows/publish", empty);
        } catch (Exception ex) {
            Map<String, Object> skipped = new LinkedHashMap<String, Object>();
            skipped.put("publishedDraft", Boolean.FALSE);
            skipped.put("reason", ex.getMessage());
            return skipped;
        }
    }

    private Object firstValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        Object data = map.get("data");
        if (data instanceof Map) {
            return firstValue((Map<String, Object>) data, keys);
        }
        return null;
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimRight(String value) {
        if (value == null) return "";
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
