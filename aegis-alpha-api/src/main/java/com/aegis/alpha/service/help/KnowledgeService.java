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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Public product knowledge index with hybrid <b>lexical</b> retrieval.
 *
 * <p>Retrieval pipeline (no neural embeddings, no synonym expansion, no semantic understanding):</p>
 * <ol>
 *   <li>ACL <b>pre-filter</b> — only documents the principal may see enter the candidate pool.
 *       Filtering never happens after scoring.</li>
 *   <li>TF-IDF vector retrieval over the candidate documents: ASCII word tokens plus CJK bigrams,
 *       {@code tf = termCount / docLength}, {@code idf = log((N + 1) / (df + 1)) + 1},
 *       L2-normalized document and query vectors, cosine similarity.</li>
 *   <li>Keyword retrieval: the legacy substring / keyword-field scorer, normalized per query.</li>
 *   <li>Reciprocal Rank Fusion of the two rankings:
 *       {@code 0.6 / (60 + rankVector) + 0.4 / (60 + rankKeyword)};
 *       a candidate missing from one ranking only receives that ranking's term.</li>
 *   <li>Deterministic rerank of the fused candidates: fused score desc, vector cosine desc,
 *       keyword score desc, doc id asc. The caller's {@code limit} is applied after rerank.</li>
 * </ol>
 *
 * <p>Because matching is purely lexical, a query only retrieves documents that share surface tokens
 * (words or CJK bigrams) with it. {@link SearchResult#getRetrievalMode()} always reports exactly
 * which pipeline produced the hits.</p>
 */
@Service
public class KnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final String LOCATION_PATTERN = "classpath:help/knowledge/*.md";
    private static final int DEFAULT_LIMIT = 5;

    /** Metadata tag describing the retrieval pipeline that produced a {@link SearchResult}. */
    public static final String RETRIEVAL_MODE_HYBRID = "hybrid_tfidf_keyword_rrf";

    /** Reciprocal Rank Fusion smoothing constant (Cormack et al.). */
    private static final int RRF_RANK_OFFSET = 60;
    private static final double RRF_VECTOR_WEIGHT = 0.6d;
    private static final double RRF_KEYWORD_WEIGHT = 0.4d;
    private static final int EXCERPT_LENGTH = 400;

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
     * Hybrid lexical search with ACL pre-filter baked into the candidate set before any scoring.
     *
     * @param query           user query
     * @param principalRoles  caller roles (may be empty)
     * @param limit           max hits, applied <em>after</em> fusion and rerank
     * @return hits already filtered to visibility allowed for this principal, plus retrieval metadata
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

        int corpusCount = documents.size();
        int candidateCount = candidates.size();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        // Empty query or empty candidate pool: no scoring, no hits — but the metadata still tells the truth.
        if (normalizedQuery.isEmpty() || candidates.isEmpty()) {
            return emptyResult(appliedFilter, candidateCount, corpusCount);
        }

        // --- keyword ranking (legacy lexical scorer, normalized per query below) ---
        int[] keywordScores = new int[candidateCount];
        int maxKeywordScore = 0;
        for (int i = 0; i < candidateCount; i++) {
            int raw = score(candidates.get(i), normalizedQuery);
            keywordScores[i] = Math.max(0, raw);
            if (keywordScores[i] > maxKeywordScore) {
                maxKeywordScore = keywordScores[i];
            }
        }

        // --- TF-IDF vector index over the ACL-filtered candidates ---
        TfIdfIndex index = TfIdfIndex.build(candidates);
        double[] cosines = index.cosineAgainst(normalizedQuery);

        List<Candidate> all = new ArrayList<Candidate>(candidateCount);
        List<Candidate> vectorRanked = new ArrayList<Candidate>();
        List<Candidate> keywordRanked = new ArrayList<Candidate>();
        for (int i = 0; i < candidateCount; i++) {
            Candidate candidate = new Candidate(candidates.get(i), i, keywordScores[i], cosines[i]);
            all.add(candidate);
            if (candidate.cosine > 0d) {
                vectorRanked.add(candidate);
            }
            if (candidate.keywordScoreRaw > 0) {
                keywordRanked.add(candidate);
            }
        }

        Collections.sort(vectorRanked, VECTOR_ORDER);
        for (int rank = 0; rank < vectorRanked.size(); rank++) {
            vectorRanked.get(rank).vectorRank = rank + 1;
        }

        Collections.sort(keywordRanked, KEYWORD_ORDER);
        for (int rank = 0; rank < keywordRanked.size(); rank++) {
            keywordRanked.get(rank).keywordRank = rank + 1;
        }

        // --- RRF fusion ---
        List<Candidate> fused = new ArrayList<Candidate>();
        for (Candidate candidate : all) {
            if (candidate.vectorRank == 0 && candidate.keywordRank == 0) {
                continue;
            }
            double rrf = 0d;
            if (candidate.vectorRank > 0) {
                rrf += RRF_VECTOR_WEIGHT / (RRF_RANK_OFFSET + candidate.vectorRank);
            }
            if (candidate.keywordRank > 0) {
                rrf += RRF_KEYWORD_WEIGHT / (RRF_RANK_OFFSET + candidate.keywordRank);
            }
            candidate.fusedScore = rrf;
            fused.add(candidate);
        }

        // --- deterministic rerank, then apply the limit ---
        Collections.sort(fused, RERANK_ORDER);

        List<KnowledgeHit> hits = new ArrayList<KnowledgeHit>();
        int shown = Math.min(cap, fused.size());
        for (int i = 0; i < shown; i++) {
            Candidate candidate = fused.get(i);
            hits.add(new KnowledgeHit(
                    candidate.doc,
                    candidate.keywordScoreRaw,
                    excerpt(candidate.doc.getBodyMarkdown(), EXCERPT_LENGTH),
                    candidate.fusedScore,
                    candidate.cosine,
                    normalizeKeywordScore(candidate.keywordScoreRaw, maxKeywordScore),
                    candidate.vectorRank,
                    candidate.keywordRank
            ));
        }
        return new SearchResult(
                hits,
                appliedFilter,
                candidateCount,
                corpusCount,
                RETRIEVAL_MODE_HYBRID,
                vectorRanked.size(),
                keywordRanked.size(),
                fused.size(),
                !fused.isEmpty()
        );
    }

    private static SearchResult emptyResult(Map<String, Object> appliedFilter, int candidateCount, int corpusCount) {
        return new SearchResult(
                Collections.<KnowledgeHit>emptyList(),
                appliedFilter,
                candidateCount,
                corpusCount,
                RETRIEVAL_MODE_HYBRID,
                0,
                0,
                0,
                false
        );
    }

    private static double normalizeKeywordScore(int raw, int maxRaw) {
        if (maxRaw <= 0 || raw <= 0) {
            return 0d;
        }
        return Math.min(1d, (double) raw / (double) maxRaw);
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

    /**
     * Legacy keyword scorer: title/id substring matches, curated keyword fields and per-token
     * title/id/body containment. Intentionally unchanged so keyword ranking stays comparable.
     */
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

    /**
     * Keyword-scoring tokenizer. Han characters are checked before the ASCII branch (Han is
     * also {@code isLetterOrDigit}), so CJK runs yield bigrams like {@code help_loop.tokenize}.
     */
    static List<String> tokenize(String lower) {
        List<String> tokens = new ArrayList<String>();
        if (lower == null || lower.isEmpty()) {
            return tokens;
        }
        StringBuilder ascii = new StringBuilder();
        int i = 0;
        while (i < lower.length()) {
            char c = lower.charAt(i);
            if (isHan(c)) {
                if (ascii.length() > 0) {
                    tokens.add(ascii.toString());
                    ascii.setLength(0);
                }
                int start = i;
                while (i < lower.length() && isHan(lower.charAt(i))) {
                    i++;
                }
                String run = lower.substring(start, i);
                if (run.length() == 1) {
                    tokens.add(run);
                } else {
                    for (int k = 0; k + 1 < run.length(); k++) {
                        tokens.add(run.substring(k, k + 2));
                    }
                }
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                ascii.append(c);
            } else if (ascii.length() > 0) {
                tokens.add(ascii.toString());
                ascii.setLength(0);
            }
            i++;
        }
        if (ascii.length() > 0) {
            tokens.add(ascii.toString());
        }
        return tokens;
    }

    // ------------------------------------------------------------------
    // TF-IDF vector space (lexical: ASCII tokens + CJK bigrams)
    // ------------------------------------------------------------------

    /**
     * Tokenizer for the vector space: lowercase ASCII word runs (length >= 2) and CJK bigrams
     * (single-character Han runs become unigrams). No stemming, no synonyms, no embeddings.
     */
    static List<String> tokenizeTerms(String text) {
        List<String> tokens = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        int i = 0;
        while (i < lower.length()) {
            char c = lower.charAt(i);
            if (isHan(c)) {
                flushAscii(ascii, tokens);
                int start = i;
                while (i < lower.length() && isHan(lower.charAt(i))) {
                    i++;
                }
                String run = lower.substring(start, i);
                if (run.length() == 1) {
                    tokens.add(run);
                } else {
                    for (int k = 0; k + 1 < run.length(); k++) {
                        tokens.add(run.substring(k, k + 2));
                    }
                }
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                ascii.append(c);
                i++;
                continue;
            }
            flushAscii(ascii, tokens);
            i++;
        }
        flushAscii(ascii, tokens);
        return tokens;
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static void flushAscii(StringBuilder buffer, List<String> tokens) {
        // Single ASCII characters carry little lexical signal; dropping them keeps the space small
        // and mirrors the "length >= 2" rule of the keyword scorer.
        if (buffer.length() >= 2) {
            tokens.add(buffer.toString());
        }
        buffer.setLength(0);
    }

    private static String vectorText(KnowledgeDoc doc) {
        String title = doc.getTitle() == null ? "" : doc.getTitle();
        String body = doc.getBodyMarkdown() == null ? "" : doc.getBodyMarkdown();
        return title + '\n' + body;
    }

    /**
     * Immutable TF-IDF index: document frequency table plus one L2-normalized weight map per doc.
     */
    private static final class TfIdfIndex {
        private final int docCount;
        private final Map<String, Integer> documentFrequency;
        private final List<Map<String, Double>> vectors;

        private TfIdfIndex(int docCount, Map<String, Integer> documentFrequency, List<Map<String, Double>> vectors) {
            this.docCount = docCount;
            this.documentFrequency = documentFrequency;
            this.vectors = vectors;
        }

        static TfIdfIndex build(List<KnowledgeDoc> docs) {
            int n = docs.size();
            Map<String, Integer> df = new HashMap<String, Integer>();
            List<Map<String, Integer>> countsPerDoc = new ArrayList<Map<String, Integer>>(n);
            List<Integer> lengths = new ArrayList<Integer>(n);
            for (KnowledgeDoc doc : docs) {
                List<String> terms = tokenizeTerms(vectorText(doc));
                Map<String, Integer> counts = new HashMap<String, Integer>();
                for (String term : terms) {
                    Integer previous = counts.get(term);
                    counts.put(term, previous == null ? 1 : previous.intValue() + 1);
                }
                countsPerDoc.add(counts);
                lengths.add(Integer.valueOf(terms.size()));
                for (String term : counts.keySet()) {
                    Integer previous = df.get(term);
                    df.put(term, previous == null ? 1 : previous.intValue() + 1);
                }
            }
            List<Map<String, Double>> vectors = new ArrayList<Map<String, Double>>(n);
            for (int i = 0; i < n; i++) {
                vectors.add(weigh(countsPerDoc.get(i), lengths.get(i).intValue(), df, n));
            }
            return new TfIdfIndex(n, df, vectors);
        }

        /**
         * Cosine similarity of every indexed document against the query.
         * Both sides are L2-normalized, so the cosine is the plain dot product.
         * Documents with an empty (zero) vector score 0 and are excluded from vector ranking.
         */
        double[] cosineAgainst(String query) {
            double[] result = new double[docCount];
            List<String> terms = tokenizeTerms(query);
            if (terms.isEmpty() || docCount <= 0) {
                return result;
            }
            Map<String, Integer> counts = new HashMap<String, Integer>();
            for (String term : terms) {
                Integer previous = counts.get(term);
                counts.put(term, previous == null ? 1 : previous.intValue() + 1);
            }
            // TreeMap keeps the summation order deterministic across runs.
            Map<String, Double> queryWeights =
                    new TreeMap<String, Double>(weigh(counts, terms.size(), documentFrequency, docCount));
            if (queryWeights.isEmpty()) {
                return result;
            }
            for (int i = 0; i < docCount; i++) {
                Map<String, Double> docWeights = vectors.get(i);
                if (docWeights.isEmpty()) {
                    continue;
                }
                double dot = 0d;
                for (Map.Entry<String, Double> entry : queryWeights.entrySet()) {
                    Double weight = docWeights.get(entry.getKey());
                    if (weight != null) {
                        dot += weight.doubleValue() * entry.getValue().doubleValue();
                    }
                }
                if (dot > 0d) {
                    result[i] = dot > 1d ? 1d : dot;
                }
            }
            return result;
        }

        private static Map<String, Double> weigh(Map<String, Integer> counts, int length,
                                                Map<String, Integer> df, int n) {
            Map<String, Double> weights = new HashMap<String, Double>();
            if (counts.isEmpty() || length <= 0) {
                return weights;
            }
            double sumSquares = 0d;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                double tf = (double) entry.getValue().intValue() / (double) length;
                double weight = tf * idf(df.get(entry.getKey()), n);
                weights.put(entry.getKey(), Double.valueOf(weight));
                sumSquares += weight * weight;
            }
            if (sumSquares <= 0d) {
                weights.clear();
                return weights;
            }
            double norm = Math.sqrt(sumSquares);
            for (Map.Entry<String, Double> entry : weights.entrySet()) {
                entry.setValue(Double.valueOf(entry.getValue().doubleValue() / norm));
            }
            return weights;
        }

        private static double idf(Integer documentFrequency, int n) {
            int df = documentFrequency == null ? 0 : documentFrequency.intValue();
            return Math.log(((double) n + 1d) / ((double) df + 1d)) + 1d;
        }
    }

    /** One ACL-filtered document with its per-ranking metrics and assigned ranks. */
    private static final class Candidate {
        final KnowledgeDoc doc;
        final int index;
        final int keywordScoreRaw;
        final double cosine;
        int vectorRank;
        int keywordRank;
        double fusedScore;

        Candidate(KnowledgeDoc doc, int index, int keywordScoreRaw, double cosine) {
            this.doc = doc;
            this.index = index;
            this.keywordScoreRaw = keywordScoreRaw;
            this.cosine = Double.isNaN(cosine) ? 0d : cosine;
        }
    }

    private static String docId(KnowledgeDoc doc) {
        return doc == null || doc.getId() == null ? "" : doc.getId();
    }

    /** Vector ranking: cosine desc, then doc id asc (insertion index as the final stable key). */
    private static final Comparator<Candidate> VECTOR_ORDER = new Comparator<Candidate>() {
        @Override
        public int compare(Candidate a, Candidate b) {
            int byCosine = Double.compare(b.cosine, a.cosine);
            if (byCosine != 0) {
                return byCosine;
            }
            return tieBreakById(a, b);
        }
    };

    /** Keyword ranking: keyword score desc, then doc id asc. */
    private static final Comparator<Candidate> KEYWORD_ORDER = new Comparator<Candidate>() {
        @Override
        public int compare(Candidate a, Candidate b) {
            int byScore = Integer.compare(b.keywordScoreRaw, a.keywordScoreRaw);
            if (byScore != 0) {
                return byScore;
            }
            return tieBreakById(a, b);
        }
    };

    /** Rerank: fused score desc, vector cosine desc, keyword score desc, doc id asc. */
    private static final Comparator<Candidate> RERANK_ORDER = new Comparator<Candidate>() {
        @Override
        public int compare(Candidate a, Candidate b) {
            int byFused = Double.compare(b.fusedScore, a.fusedScore);
            if (byFused != 0) {
                return byFused;
            }
            int byCosine = Double.compare(b.cosine, a.cosine);
            if (byCosine != 0) {
                return byCosine;
            }
            int byKeyword = Integer.compare(b.keywordScoreRaw, a.keywordScoreRaw);
            if (byKeyword != 0) {
                return byKeyword;
            }
            return tieBreakById(a, b);
        }
    };

    private static int tieBreakById(Candidate a, Candidate b) {
        int byId = docId(a.doc).compareTo(docId(b.doc));
        if (byId != 0) {
            return byId;
        }
        return Integer.compare(a.index, b.index);
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
        FrontMatter front = FrontMatter.parse(raw, filename);
        KnowledgeDoc doc = new KnowledgeDoc();
        String id = front.string("id");
        if (id == null) {
            id = filename == null ? null : filename.replace(".md", "");
        }
        doc.setId(id);
        doc.setTitle(front.string("title", id == null ? "doc" : id));
        doc.setVisibility(front.string("visibility", "public"));
        doc.setKeywords(front.list("keywords"));
        doc.setAllowedRoles(front.list("allowedRoles"));
        doc.setBodyMarkdown(front.body().trim());
        return doc;
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

    /**
     * A retrieval hit. {@code score} keeps the legacy raw keyword score; the hybrid diagnostics live in
     * {@link #getFusedScore()}, {@link #getVectorScore()}, {@link #getKeywordScore()} and the two ranks.
     */
    public static final class KnowledgeHit {
        private final KnowledgeDoc doc;
        private final int score;
        private final String excerpt;
        private final double fusedScore;
        private final double vectorScore;
        private final double keywordScore;
        private final int vectorRank;
        private final int keywordRank;

        /** Legacy constructor: raw keyword score only, no hybrid metadata. */
        public KnowledgeHit(KnowledgeDoc doc, int score, String excerpt) {
            this(doc, score, excerpt, 0d, 0d, 0d, 0, 0);
        }

        public KnowledgeHit(KnowledgeDoc doc, int score, String excerpt, double fusedScore,
                            double vectorScore, double keywordScore, int vectorRank, int keywordRank) {
            this.doc = doc;
            this.score = score;
            this.excerpt = excerpt;
            this.fusedScore = fusedScore;
            this.vectorScore = vectorScore;
            this.keywordScore = keywordScore;
            this.vectorRank = vectorRank;
            this.keywordRank = keywordRank;
        }

        public KnowledgeDoc getDoc() {
            return doc;
        }

        /** Legacy raw keyword score (int, un-normalized). */
        public int getScore() {
            return score;
        }

        public String getExcerpt() {
            return excerpt;
        }

        /** Reciprocal Rank Fusion score used for ordering (higher is better). */
        public double getFusedScore() {
            return fusedScore;
        }

        /** TF-IDF cosine similarity in [0, 1]; 0 when the document shares no lexical token. */
        public double getVectorScore() {
            return vectorScore;
        }

        /** Keyword score normalized per query in [0, 1] (raw score / best raw score). */
        public double getKeywordScore() {
            return keywordScore;
        }

        /** 1-based position in the vector ranking; 0 when the doc is absent from that ranking. */
        public int getVectorRank() {
            return vectorRank;
        }

        /** 1-based position in the keyword ranking; 0 when the doc is absent from that ranking. */
        public int getKeywordRank() {
            return keywordRank;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", doc == null ? null : doc.getId());
            m.put("title", doc == null ? null : doc.getTitle());
            m.put("visibility", doc == null ? null : doc.getVisibility());
            m.put("score", score);
            m.put("excerpt", excerpt);
            m.put("text", excerpt);
            m.put("fusedScore", fusedScore);
            m.put("vectorScore", vectorScore);
            m.put("keywordScore", keywordScore);
            m.put("vectorRank", vectorRank);
            m.put("keywordRank", keywordRank);
            return m;
        }
    }

    /**
     * Search output: hits plus the ACL filter that produced the candidate pool and the retrieval
     * metadata (mode, per-retriever candidate counts, fusion and rerank flags).
     */
    public static final class SearchResult {
        private final List<KnowledgeHit> hits;
        private final Map<String, Object> appliedFilter;
        private final int candidateCount;
        private final int corpusCount;
        private final String retrievalMode;
        private final int vectorCandidates;
        private final int keywordCandidates;
        private final int fusedCandidates;
        private final boolean reranked;

        /** Legacy constructor: metadata reported as hybrid mode with no per-stage counts. */
        public SearchResult(List<KnowledgeHit> hits, Map<String, Object> appliedFilter,
                            int candidateCount, int corpusCount) {
            this(hits, appliedFilter, candidateCount, corpusCount, RETRIEVAL_MODE_HYBRID, 0, 0, 0, false);
        }

        public SearchResult(List<KnowledgeHit> hits, Map<String, Object> appliedFilter,
                            int candidateCount, int corpusCount, String retrievalMode,
                            int vectorCandidates, int keywordCandidates, int fusedCandidates,
                            boolean reranked) {
            this.hits = hits == null ? Collections.<KnowledgeHit>emptyList() : hits;
            this.appliedFilter = appliedFilter == null
                    ? Collections.<String, Object>emptyMap()
                    : appliedFilter;
            this.candidateCount = candidateCount;
            this.corpusCount = corpusCount;
            this.retrievalMode = retrievalMode == null ? RETRIEVAL_MODE_HYBRID : retrievalMode;
            this.vectorCandidates = vectorCandidates;
            this.keywordCandidates = keywordCandidates;
            this.fusedCandidates = fusedCandidates;
            this.reranked = reranked;
        }

        public List<KnowledgeHit> getHits() {
            return hits;
        }

        public Map<String, Object> getAppliedFilter() {
            return appliedFilter;
        }

        /** Documents that survived the ACL pre-filter (the scoring pool size). */
        public int getCandidateCount() {
            return candidateCount;
        }

        public int getCorpusCount() {
            return corpusCount;
        }

        /** Retrieval pipeline tag, e.g. {@link KnowledgeService#RETRIEVAL_MODE_HYBRID}. */
        public String getRetrievalMode() {
            return retrievalMode;
        }

        /** Documents with a non-zero TF-IDF cosine against the query. */
        public int getVectorCandidates() {
            return vectorCandidates;
        }

        /** Documents with a non-zero keyword score against the query. */
        public int getKeywordCandidates() {
            return keywordCandidates;
        }

        /** Unique documents that entered RRF fusion (union of both rankings). */
        public int getFusedCandidates() {
            return fusedCandidates;
        }

        /** True when the deterministic rerank actually ordered at least one fused candidate. */
        public boolean isReranked() {
            return reranked;
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
            m.put("retrievalMode", retrievalMode);
            m.put("vectorCandidates", vectorCandidates);
            m.put("keywordCandidates", keywordCandidates);
            m.put("fusedCandidates", fusedCandidates);
            m.put("reranked", reranked);
            return m;
        }
    }
}
