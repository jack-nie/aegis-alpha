package com.aegis.alpha.service.help;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads product-help playbooks from classpath:help/playbooks/*.md
 */
@Service
public class PlaybookService {
    private static final Logger log = LoggerFactory.getLogger(PlaybookService.class);
    private static final String LOCATION_PATTERN = "classpath:help/playbooks/*.md";

    private volatile List<Playbook> playbooks = Collections.emptyList();

    @PostConstruct
    public void load() {
        reload();
    }

    public void reload() {
        List<Playbook> loaded = new ArrayList<Playbook>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(LOCATION_PATTERN);
            for (Resource resource : resources) {
                if (resource == null || !resource.exists()) {
                    continue;
                }
                Playbook playbook = parse(readResource(resource), resource.getFilename());
                if (playbook != null && playbook.getId() != null) {
                    loaded.add(playbook);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to load help playbooks: {}", ex.toString());
        }
        this.playbooks = Collections.unmodifiableList(loaded);
        log.info("Loaded {} help playbooks", this.playbooks.size());
    }

    public List<Playbook> list() {
        return playbooks;
    }

    public Playbook findById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Playbook playbook : playbooks) {
            if (id.equals(playbook.getId())) {
                return playbook;
            }
        }
        return null;
    }

    /**
     * Longest keyword match wins (same idea as workflow keyword routing).
     */
    public Playbook findBestMatch(String message) {
        if (message == null || message.trim().isEmpty() || playbooks.isEmpty()) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        Playbook best = null;
        int bestLength = 0;
        for (Playbook playbook : playbooks) {
            for (String keyword : playbook.getKeywords()) {
                if (keyword == null || keyword.isEmpty()) {
                    continue;
                }
                String k = keyword.toLowerCase(Locale.ROOT);
                if (lower.contains(k) && k.length() > bestLength) {
                    bestLength = k.length();
                    best = playbook;
                }
            }
        }
        return best;
    }

    static Playbook parse(String raw, String filename) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String content = raw.replace("\r\n", "\n");
        Map<String, String> front = new LinkedHashMap<String, String>();
        String body = content;
        if (content.startsWith("---\n")) {
            int end = content.indexOf("\n---\n", 4);
            if (end > 0) {
                String yaml = content.substring(4, end);
                body = content.substring(end + 5);
                parseFrontMatter(yaml, front);
            }
        }
        Playbook playbook = new Playbook();
        String id = front.get("id");
        if (id == null || id.isEmpty()) {
            id = filename == null ? null : filename.replace(".md", "");
        }
        playbook.setId(id);
        playbook.setTitle(front.getOrDefault("title", id == null ? "Playbook" : id));
        playbook.setDeepLink(front.get("deepLink"));
        playbook.setKeywords(parseKeywords(front.get("keywords")));
        playbook.setBodyMarkdown(body.trim());
        return playbook;
    }

    private static void parseFrontMatter(String yaml, Map<String, String> out) {
        String currentKey = null;
        StringBuilder listBuf = null;
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("- ") && currentKey != null) {
                String item = trimmed.substring(2).trim();
                if (listBuf == null) {
                    listBuf = new StringBuilder();
                } else if (listBuf.length() > 0) {
                    listBuf.append(",");
                }
                listBuf.append(item);
                continue;
            }
            if (listBuf != null && currentKey != null) {
                out.put(currentKey, listBuf.toString());
                listBuf = null;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            currentKey = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty()) {
                listBuf = new StringBuilder();
            } else {
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(currentKey, value);
                currentKey = null;
            }
        }
        if (listBuf != null && currentKey != null) {
            out.put(currentKey, listBuf.toString());
        }
    }

    private static List<String> parseKeywords(String raw) {
        List<String> list = new ArrayList<String>();
        if (raw == null || raw.trim().isEmpty()) {
            return list;
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        for (String part : cleaned.split(",")) {
            String item = part.trim();
            if ((item.startsWith("\"") && item.endsWith("\""))
                    || (item.startsWith("'") && item.endsWith("'"))) {
                item = item.substring(1, item.length() - 1);
            }
            if (!item.isEmpty()) {
                list.add(item);
            }
        }
        return list;
    }

    private static String readResource(Resource resource) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static final class Playbook {
        private String id;
        private String title;
        private List<String> keywords = new ArrayList<String>();
        private String bodyMarkdown;
        private String deepLink;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public List<String> getKeywords() {
            return keywords == null ? Collections.<String>emptyList() : keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords == null ? new ArrayList<String>() : keywords;
        }

        public String getBodyMarkdown() {
            return bodyMarkdown;
        }

        public void setBodyMarkdown(String bodyMarkdown) {
            this.bodyMarkdown = bodyMarkdown;
        }

        public String getDeepLink() {
            return deepLink;
        }

        public void setDeepLink(String deepLink) {
            this.deepLink = deepLink;
        }
    }
}
