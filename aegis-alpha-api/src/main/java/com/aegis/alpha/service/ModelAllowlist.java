package com.aegis.alpha.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * P4-6 (R5): server-side control of user-supplied model names (chat body.modelName, agent modelName).
 * The orchestrator registry (app/core/models.yaml) is the second gate; it falls back visibly
 * (modelFallbackReason) instead of the old silent DeepSeek rewrite.
 * 待确认: default list mirrors the orchestrator registry (deepseek-v4-flash, deepseek-v4-pro).
 */
@Component
public class ModelAllowlist {
    public static final String DEFAULT_ALLOWED = "deepseek-v4-flash,deepseek-v4-pro";

    private volatile Set<String> allowed = parse(DEFAULT_ALLOWED);

    public static ModelAllowlist defaults() {
        return new ModelAllowlist();
    }

    public static ModelAllowlist of(String csv) {
        ModelAllowlist list = new ModelAllowlist();
        list.setAllowedNames(csv);
        return list;
    }

    @Value("${aegis.models.allowed-names:" + DEFAULT_ALLOWED + "}")
    public void setAllowedNames(String csv) {
        Set<String> parsed = parse(csv);
        // An empty list would silently allow nothing (every request falls back); keep the defaults instead.
        this.allowed = parsed.isEmpty() ? parse(DEFAULT_ALLOWED) : parsed;
    }

    public boolean isAllowed(String modelName) {
        return modelName != null && allowed.contains(modelName.trim());
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(allowed);
    }

    private static Set<String> parse(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                if (!part.trim().isEmpty()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }
}
