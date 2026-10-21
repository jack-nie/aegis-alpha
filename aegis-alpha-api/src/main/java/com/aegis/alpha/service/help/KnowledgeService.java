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
 * Public product knowledge index (keyword / in-memory).
 * ACL is applied as a <b>pre-filter</b> before ranking — never retrieve then drop all hits.
 */
@Service
public class KnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final String LOCATION_PATTERN = "classpath:help/knowledge/*.md";
    private static final int DEFAULT_LIMIT = 5;

    private volatile List<KnowledgeDoc> documents = Collections.emptyList();

    @PostConstruct
    public void load() {
        reload();
    }

    public void reload() {
        List<KnowledgeDoc> loaded = new ArrayList<KnowledgeDoc>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(LOCATION_PATTERN);
            for (Resource resource : resources) {
                if (resource == null || !resource.exists()) {
                    continue;
                }
                KnowledgeDoc doc = parse(readResource(resource), resource.getFilename());
                if (doc != null && doc.getId() != null) {
                    loaded.add(doc);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to load help knowledge docs: {}", ex.toString());
        }
        this.documents = Collections.unmodifiableList(loaded);
        log.info("Loaded {} help knowledge documents", this.documents.size());
    }

    public List<KnowledgeDoc> listAll() {
        return documents;
    }

    /**
     * Search with ACL pre-filter baked into candidate set before scoring.
     *
     * @param query           user query
     * @param principalRoles  caller roles (may be empty)
     * @param limit           max hits
     * @return hits already filtered to visibility allowed for this principal
     */
    public SearchResult search(String query, List<String> principalRoles, int limit) {
        int cap = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 20);
        List<String> roles = principalRoles == null
                ? Collections.<String>emptyList()
                : principalRoles;

        // PRE-FILTER: only documents this principal may see enter the candidate pool.
        List<KnowledgeDoc> candidates = new ArrayList<KnowledgeDoc>();
        Map<String, Object> appliedFilter = new LinkedHashMap<String, Object>();
        appliedFilter.put("visibilityIn", Collections.singletonList("public"));
        appliedFilter.put("orRolesIntersectPrincipal", true);
        appliedFilter.put("principalRoles", roles);

        for (KnowledgeDoc doc : documents) {
            if (isAllowed(doc, roles)) {
                candidates.add(doc);
            }
        }

        List<KnowledgeHit> hits = new ArrayList<KnowledgeHit>();
        if (query == null || query.trim().isEmpty() || candidates.isEmpty()) {
            return new SearchResult(hits, appliedFilter, candidates.size(), documents.size());
        }

        String lower = query.toLowerCase(Locale.ROOT);
        List<Scored> scored = new ArrayList<Scored>();
        for (KnowledgeDoc doc : candidates) {
            int score = score(doc, lower);
            if (score > 0) {
                scored.add(new Scored(doc, score));
            }
        }
        Collections.sort(scored);
        for (int i = 0; i < scored.size() && hits.size() < cap; i++) {
            Scored s = scored.get(i);
            hits.add(new KnowledgeHit(s.doc, s.score, excerpt(s.doc.getBodyMarkdown(), 400)));
        }
        return new SearchResult(hits, appliedFilter, candidates.size(), documents.size());
    }

    /**
     * Exposed for unit tests: ACL pre-filter only.
     */
    public List<KnowledgeDoc> preFilter(List<String> principalRoles) {
        List<String> roles = principalRoles == null
                ? Collections.<String>emptyList()
                : principalRoles;
        List<KnowledgeDoc> out = new ArrayList<KnowledgeDoc>();
        for (KnowledgeDoc doc : documents) {
            if (isAllowed(doc, roles)) {
                out.add(doc);
            }
        }
        return out;
    }

    static boolean isAllowed(KnowledgeDoc doc, List<String> principalRoles) {
        if (doc == null) {
            return false;
        }
        String visibility = doc.getVisibility() == null ? "public" : doc.getVisibility().trim().toLowerCase(Locale.ROOT);
        if ("public".equals(visibility)) {
            return true;
        }
        if ("internal".equals(visibility) || "role".equals(visibility)) {
            if (principalRoles == null || principalRoles.isEmpty()) {
                return false;
            }
            for (String allowed : doc.getAllowedRoles()) {
                if (principalRoles.contains(allowed)) {
                    return true;
                }
            }
            return false;
        }
        // private / unknown — never from public knowledge index
        return false;
    }

    private static int score(KnowledgeDoc doc, String lowerQuery) {
        int score = 0;
        String title = doc.getTitle() == null ? "" : doc.getTitle().toLowerCase(Locale.ROOT);
        String body = doc.getBodyMarkdown() == null ? "" : doc.getBodyMarkdown().toLowerCase(Locale.ROOT);
        String id = doc.getId() == null ? "" : doc.getId().toLowerCase(Locale.ROOT);
        if (title.contains(lowerQuery) || lowerQuery.contains(title) && title.length() > 2) {
            score += 10;
        }
        for (String kw : doc.getKeywords()) {
            if (kw != null && !kw.isEmpty() && lowerQuery.contains(kw.toLowerCase(Locale.ROOT))) {
                score += 5 + Math.min(kw.length(), 20);
            }
        }
        for (String token : tokenize(lowerQuery)) {
            if (token.length() < 2) {
                continue;
            }
            if (title.contains(token)) {
                score += 3;
            }
            if (id.contains(token)) {
                score += 2;
            }
            if (body.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private static List<String> tokenize(String lower) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                ascii.append(c);
            } else {
                if (ascii.length() > 0) {
                    tokens.add(ascii.toString());
                    ascii.setLength(0);
                }
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    // CJK bigrams
                    if (i + 1 < lower.length()
                            && Character.UnicodeScript.of(lower.charAt(i + 1)) == Character.UnicodeScript.HAN) {
                        tokens.add(lower.substring(i, i + 2));
                    }
                }
            }
        }
        if (ascii.length() > 0) {
            tokens.add(ascii.toString());
        }
        return tokens;
    }

    private static String excerpt(String body, int max) {
        if (body == null) {
            return "";
        }
        String cleaned = body.trim().replace("\r\n", "\n");
        if (cleaned.length() <= max) {
            return cleaned;
        }
        return cleaned.substring(0, max - 1) + "…";
    }

    static KnowledgeDoc parse(String raw, String filename) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String content = raw.replace("\r\n", "\n");
        Map<String, String> front = new LinkedHashMap<String, String>();
        String body = content;
        if (content.startsWith("---\n")) {
            int end = content.indexOf("\n---\n", 4);
            if (end > 0) {
                parseFrontMatter(content.substring(4, end), front);
                body = content.substring(end + 5);
            }
        }
        KnowledgeDoc doc = new KnowledgeDoc();
        String id = front.get("id");
        if (id == null || id.isEmpty()) {
            id = filename == null ? null : filename.replace(".md", "");
        }
        doc.setId(id);
        doc.setTitle(front.getOrDefault("title", id == null ? "doc" : id));
        doc.setVisibility(front.getOrDefault("visibility", "public"));
        doc.setKeywords(parseList(front.get("keywords")));
        doc.setAllowedRoles(parseList(front.get("allowedRoles")));
        doc.setBodyMarkdown(body.trim());
        return doc;
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
                if (listBuf == null) {
                    listBuf = new StringBuilder();
                } else if (listBuf.length() > 0) {
                    listBuf.append(",");
                }
                listBuf.append(trimmed.substring(2).trim());
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

    private static List<String> parseList(String raw) {
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

    /** Package-private helper for tests injecting docs without classpath. */
    void replaceDocumentsForTest(List<KnowledgeDoc> docs) {
        this.documents = docs == null
                ? Collections.<KnowledgeDoc>emptyList()
                : Collections.unmodifiableList(new ArrayList<KnowledgeDoc>(docs));
    }

    private static final class Scored implements Comparable<Scored> {
        final KnowledgeDoc doc;
        final int score;

        Scored(KnowledgeDoc doc, int score) {
            this.doc = doc;
            this.score = score;
        }

        @Override
        public int compareTo(Scored o) {
            return Integer.compare(o.score, this.score);
        }
    }

    public static final class KnowledgeDoc {
        private String id;
        private String title;
        private String visibility = "public";
        private List<String> keywords = new ArrayList<String>();
        private List<String> allowedRoles = new ArrayList<String>();
        private String bodyMarkdown;

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

        public String getVisibility() {
            return visibility;
        }

        public void setVisibility(String visibility) {
            this.visibility = visibility;
        }

        public List<String> getKeywords() {
            return keywords == null ? Collections.<String>emptyList() : keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords == null ? new ArrayList<String>() : keywords;
        }

        public List<String> getAllowedRoles() {
            return allowedRoles == null ? Collections.<String>emptyList() : allowedRoles;
        }

        public void setAllowedRoles(List<String> allowedRoles) {
            this.allowedRoles = allowedRoles == null ? new ArrayList<String>() : allowedRoles;
        }

        public String getBodyMarkdown() {
            return bodyMarkdown;
        }

        public void setBodyMarkdown(String bodyMarkdown) {
            this.bodyMarkdown = bodyMarkdown;
        }
    }

    public static final class KnowledgeHit {
        private final KnowledgeDoc doc;
        private final int score;
        private final String excerpt;

        public KnowledgeHit(KnowledgeDoc doc, int score, String excerpt) {
            this.doc = doc;
            this.score = score;
            this.excerpt = excerpt;
        }

        public KnowledgeDoc getDoc() {
            return doc;
        }

        public int getScore() {
            return score;
        }

        public String getExcerpt() {
            return excerpt;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", doc == null ? null : doc.getId());
            m.put("title", doc == null ? null : doc.getTitle());
            m.put("visibility", doc == null ? null : doc.getVisibility());
            m.put("score", score);
            m.put("excerpt", excerpt);
            m.put("text", excerpt);
            return m;
        }
    }

    public static final class SearchResult {
        private final List<KnowledgeHit> hits;
        private final Map<String, Object> appliedFilter;
        private final int candidateCount;
        private final int corpusCount;

        public SearchResult(List<KnowledgeHit> hits, Map<String, Object> appliedFilter,
                            int candidateCount, int corpusCount) {
            this.hits = hits == null ? Collections.<KnowledgeHit>emptyList() : hits;
            this.appliedFilter = appliedFilter == null
                    ? Collections.<String, Object>emptyMap()
                    : appliedFilter;
            this.candidateCount = candidateCount;
            this.corpusCount = corpusCount;
        }

        public List<KnowledgeHit> getHits() {
            return hits;
        }

        public Map<String, Object> getAppliedFilter() {
            return appliedFilter;
        }

        public int getCandidateCount() {
            return candidateCount;
        }

        public int getCorpusCount() {
            return corpusCount;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> hitMaps = new ArrayList<Map<String, Object>>();
            for (KnowledgeHit hit : hits) {
                hitMaps.add(hit.toMap());
            }
            m.put("hits", hitMaps);
            m.put("appliedFilter", appliedFilter);
            m.put("candidateCount", candidateCount);
            m.put("corpusCount", corpusCount);
            m.put("preFiltered", true);
            return m;
        }
    }
}
