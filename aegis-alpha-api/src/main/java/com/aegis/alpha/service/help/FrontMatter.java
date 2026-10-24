package com.aegis.alpha.service.help;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3-2: shared Markdown front-matter parsing (SnakeYAML, safe constructor) for playbooks and
 * knowledge docs. Replaces two hand-written line parsers.
 */
final class FrontMatter {
    private static final Logger log = LoggerFactory.getLogger(FrontMatter.class);

    private final Map<String, Object> fields;
    private final String body;

    private FrontMatter(Map<String, Object> fields, String body) {
        this.fields = fields;
        this.body = body;
    }

    /** Splits {@code ---\n...\n---\n} front matter from the body; malformed YAML yields no fields. */
    static FrontMatter parse(String raw, String source) {
        String content = raw == null ? "" : raw.replace("\r\n", "\n");
        if (!content.startsWith("---\n")) {
            return new FrontMatter(Collections.<String, Object>emptyMap(), content);
        }
        int end = content.indexOf("\n---\n", 4);
        if (end <= 0) {
            return new FrontMatter(Collections.<String, Object>emptyMap(), content);
        }
        String yaml = content.substring(4, end);
        String body = content.substring(end + 5);
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        try {
            Object loaded = new Yaml(new SafeConstructor()).load(yaml);
            if (loaded instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) loaded).entrySet()) {
                    if (entry.getKey() != null) {
                        fields.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Invalid front matter in {}: {}", source, ex.getMessage());
        }
        return new FrontMatter(fields, body);
    }

    String body() {
        return body;
    }

    String string(String key) {
        Object value = fields.get(key);
        if (value == null || value instanceof Map || value instanceof Collection) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    String string(String key, String fallback) {
        String value = string(key);
        return value == null ? fallback : value;
    }

    /** YAML list, or a scalar comma list ({@code a, b}) for backwards compatibility. */
    List<String> list(String key) {
        Object value = fields.get(key);
        List<String> out = new ArrayList<String>();
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                addItem(out, item);
            }
        } else if (value != null && !(value instanceof Map)) {
            for (String part : String.valueOf(value).split(",")) {
                addItem(out, part);
            }
        }
        return out;
    }

    private static void addItem(List<String> out, Object item) {
        if (item == null) {
            return;
        }
        String text = String.valueOf(item).trim();
        if (!text.isEmpty()) {
            out.add(text);
        }
    }
}
