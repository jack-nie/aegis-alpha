package com.aegis.alpha.service;

import com.aegis.alpha.config.AsyncConfig;
import com.aegis.alpha.domain.AgentTemplate;
import com.aegis.alpha.domain.HelpSessionState;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.ChatMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.help.HelpAnswerCacheService;
import com.aegis.alpha.service.help.HelpIntent;
import com.aegis.alpha.service.help.HelpRoutingService;
import com.aegis.alpha.service.help.HelpService;
import com.aegis.alpha.service.help.HelpSessionStateService;
import com.aegis.alpha.service.help.trace.HelpTrace;
import com.aegis.alpha.service.help.trace.HelpTraceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatService {
    /** P2-11: Copilot system prompt lives in a resource; missing data must be stated explicitly. */
    static final String COPILOT_PROMPT_RESOURCE = "prompts/copilot-system.md";
    static final String COPILOT_SYSTEM_PROMPT = loadCopilotPrompt();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatService.class);
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}(?:[._-][A-Z])?\\b");


    /** Identity fields that must never be taken from the client-supplied {@code state}. */
    private static final Set<String> CLIENT_IDENTITY_KEYS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "userId", "user_id", "tenantId", "tenant_id", "roles", "role", "username"
    )));

    private final ChatMapper mapper;
    private final LangChainGateway langChainGateway;
    private final MarketDataService marketDataService;
    private final WorkflowService workflowService;
    private final HelpRoutingService helpRoutingService;
    private final HelpService helpService;
    private final HelpAnswerCacheService helpAnswerCacheService;
    private final HelpSessionStateService helpSessionStateService;
    private final HelpTraceService helpTraceService;
    private final Executor workflowExecutor;

    private ModelAllowlist modelAllowlist = ModelAllowlist.defaults();

    @Autowired(required = false)
    void setModelAllowlist(ModelAllowlist modelAllowlist) {
        if (modelAllowlist != null) {
            this.modelAllowlist = modelAllowlist;
        }
    }

    @Autowired
    public ChatService(ChatMapper mapper,
                       LangChainGateway langChainGateway,
                       MarketDataService marketDataService,
                       WorkflowService workflowService,
                       HelpRoutingService helpRoutingService,
                       HelpService helpService,
                       HelpAnswerCacheService helpAnswerCacheService,
                       HelpSessionStateService helpSessionStateService,
                       HelpTraceService helpTraceService,
                       @Qualifier(AsyncConfig.WORKFLOW_EXECUTOR) Executor workflowExecutor) {
        this.mapper = mapper;
        this.langChainGateway = langChainGateway;
        this.marketDataService = marketDataService;
        this.workflowService = workflowService;
        this.helpRoutingService = helpRoutingService;
        this.helpService = helpService;
        this.helpAnswerCacheService = helpAnswerCacheService;
        this.helpSessionStateService = helpSessionStateService;
        this.helpTraceService = helpTraceService;
        this.workflowExecutor = workflowExecutor;
    }

    /** Backward-compatible constructor for unit tests. */
    public ChatService(ChatMapper mapper,
                       LangChainGateway langChainGateway,
                       MarketDataService marketDataService,
                       WorkflowService workflowService,
                       HelpRoutingService helpRoutingService,
                       HelpService helpService,
                       HelpAnswerCacheService helpAnswerCacheService) {
        this(mapper, langChainGateway, marketDataService, workflowService, helpRoutingService, helpService,
                helpAnswerCacheService, null, null, null);
    }

    /** Backward-compatible constructor for older unit tests. */
    public ChatService(ChatMapper mapper,
                       LangChainGateway langChainGateway,
                       MarketDataService marketDataService,
                       WorkflowService workflowService,
                       HelpRoutingService helpRoutingService,
                       HelpService helpService) {
        this(mapper, langChainGateway, marketDataService, workflowService, helpRoutingService, helpService,
                null, null, null, null);
    }

    public Object threads() {
        return threads(null);
    }

    /**
     * Thread list for the authenticated caller. A null principal keeps the legacy
     * unscoped list for internal/test callers; HTTP callers always pass a principal.
     */
    public Object threads(AuthenticatedPrincipal principal) {
        if (principal == null) {
            return mapper.threads();
        }
        return mapper.threadsForPrincipal(principal.getUserId(), principal.getTenantId());
    }

    /** Backward-compatible entry point for tests / internal callers without a principal. */
    public Map<String, Object> reply(Map<String, Object> body) {
        return reply(body, null);
    }

    public Map<String, Object> reply(Map<String, Object> body, AuthenticatedPrincipal principal) {
        String message = text(body, "message", "");
        String threadId = resolveThreadId(body, message, principal);
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "user", message);

        HelpTrace trace = helpTraceService == null
                ? null
                : helpTraceService.start(UUID.randomUUID().toString(), threadId);
        String previousTraceId = org.slf4j.MDC.get("traceId");
        if (trace != null) {
            org.slf4j.MDC.put("traceId", trace.getTraceId());
        }
        try {
            return replyInternal(body, principal, message, threadId, trace);
        } finally {
            if (trace != null) {
                if (previousTraceId == null) {
                    org.slf4j.MDC.remove("traceId");
                } else {
                    org.slf4j.MDC.put("traceId", previousTraceId);
                }
            }
            if (helpTraceService != null) {
                helpTraceService.finish(trace);
            }
        }
    }

    private Map<String, Object> replyInternal(Map<String, Object> body,
                                              AuthenticatedPrincipal principal,
                                              String message,
                                              String threadId,
                                              HelpTrace trace) {
        HelpIntent.PageContext pageContext = HelpIntent.PageContext.fromMap(objectMap(body == null ? null : body.get("pageContext")));
        HelpSessionState session = loadSession(threadId, principal);

        /* Safety refusals first (no side effects) */
        Map<String, Object> safety = helpService.safetyRefuse(threadId, message, null);
        if (safety != null) {
            markTrace(trace, HelpIntent.Domain.PRODUCT_HELP, HelpIntent.RouteAction.PLAYBOOK,
                    text(safety, "provider", "help-safety"), false, false);
            mergeDomainIfMissing(safety, HelpIntent.Domain.PRODUCT_HELP, HelpIntent.RouteAction.PLAYBOOK);
            persistAssistant(threadId, text(safety, "content", ""));
            recordSession(threadId, principal, session, message, null, safety, false);
            return safety;
        }

        /* Explicit workflowKey override takes priority */
        String explicit = text(body, "workflowKey", "");

        /*
         * pi-style message queue: while the workflow run this thread dispatched is still RUNNING, a new
         * chat message is queued into that agent loop ({"delivery": "follow_up"} by default, or "steer")
         * instead of being routed again. Opt out per message with {"delivery": "new"} or a workflowKey.
         */
        if (explicit.isEmpty()) {
            Map<String, Object> queued = tryQueueIntoActiveRun(body, principal, session, message, threadId, trace);
            if (queued != null) {
                return queued;
            }
        }

        if (!explicit.isEmpty()) {
            com.aegis.alpha.service.help.RoutingTelemetry.logRoute(trace == null ? null : trace.getTraceId(),
                    HelpIntent.Domain.RESEARCH.name(), HelpIntent.RouteAction.DISPATCH_WORKFLOW.name(),
                    "explicit_workflow_key", 1.0d, 0L, false, false, message);
            Map<String, Object> response = dispatchWorkflow(explicit, message, threadId, body, principal, trace);
            decorateResearch(response, explicit);
            recordSession(threadId, principal, session, message, null, response, false);
            return response;
        }

        HelpIntent.Result intent = helpRoutingService.classifyExtended(message, pageContext);
        applyPreferredDomain(body, intent);
        applySessionRunId(intent, session);

        /* Out of scope: fixed decline, never the Copilot LLM (P2-7) */
        if (intent.getDomain() == HelpIntent.Domain.OUT_OF_SCOPE) {
            Map<String, Object> declined = helpService.outOfScope(threadId, intent);
            markTrace(trace, intent, text(declined, "provider", "help-out-of-scope"), false, false);
            persistAssistant(threadId, text(declined, "content", ""));
            recordSession(threadId, principal, session, message, intent, declined, false);
            return declined;
        }

        /* Public help answer cache (entity-gated; never private run/portfolio) */
        if (helpAnswerCacheService != null) {
            Map<String, Object> cached = helpAnswerCacheService.getIfAllowed(message, intent, principal);
            if (cached != null) {
                cached.put("threadId", threadId);
                cached.put("cacheHit", true);
                markTrace(trace, intent, text(cached, "provider", "help-cache"), true,
                        Boolean.TRUE.equals(cached.get("escalateHuman")));
                persistAssistant(threadId, text(cached, "content", ""));
                recordSession(threadId, principal, session, message, intent, cached, false);
                return cached;
            }
        }

        /* Roles always come from the authenticated principal, never from the request body. */
        List<String> roles = rolesOf(principal);
        Map<String, Object> helpResponse = null;

        switch (intent.getRouteAction()) {
            case DISPATCH_WORKFLOW:
                if (intent.getWorkflowKey() != null && !intent.getWorkflowKey().isEmpty()) {
                    Map<String, Object> response = dispatchWorkflow(intent.getWorkflowKey(), message, threadId, body, principal, trace);
                    decorateFromIntent(response, intent);
                    recordSession(threadId, principal, session, message, intent, response, false);
                    return response;
                }
                break;
            case CLARIFY:
                Map<String, Object> clarify = helpService.clarify(threadId, intent);
                markTrace(trace, intent, text(clarify, "provider", "help-clarify"), false, false);
                persistAssistant(threadId, text(clarify, "content", ""));
                recordSession(threadId, principal, session, message, intent, clarify, true);
                return clarify;
            case PLAYBOOK: {
                helpResponse = helpService.answerFromPlaybook(threadId, message, intent);
                if (helpResponse == null) {
                    helpResponse = helpService.answerFromKnowledge(threadId, message, intent, roles);
                }
                if (helpResponse == null) {
                    helpResponse = helpService.answerFromHelpLoop(threadId, message, intent, roles);
                }
                break;
            }
            case HELP_GRAPH:
                if (intent.getDomain() == HelpIntent.Domain.RUN_DIAGNOSIS) {
                    Map<String, Object> diagnosis = helpService.diagnoseRun(threadId, message, intent, principal);
                    markTrace(trace, intent, text(diagnosis, "provider", "help-diagnosis"), false,
                            Boolean.TRUE.equals(diagnosis.get("escalateHuman")));
                    markRetrievalRounds(trace, diagnosis);
                    persistAssistant(threadId, text(diagnosis, "content", ""));
                    recordSession(threadId, principal, session, message, intent, diagnosis, false);
                    return diagnosis;
                }
                helpResponse = helpService.answerFromPlaybook(threadId, message, intent);
                if (helpResponse == null) {
                    helpResponse = helpService.answerFromHelpLoop(threadId, message, intent, roles);
                }
                break;
            case KNOWLEDGE_RAG:
                helpResponse = helpService.answerFromKnowledge(threadId, message, intent, roles);
                if (helpResponse == null) {
                    helpResponse = helpService.answerFromHelpLoop(threadId, message, intent, roles);
                }
                break;
            case COPILOT:
            default:
                break;
        }

        if (helpResponse != null) {
            if (helpAnswerCacheService != null) {
                helpAnswerCacheService.putIfAllowed(message, intent, helpResponse, principal);
            }
            markTrace(trace, intent, text(helpResponse, "provider", "help"), false,
                    Boolean.TRUE.equals(helpResponse.get("escalateHuman")));
            markRetrievalRounds(trace, helpResponse);
            persistAssistant(threadId, text(helpResponse, "content", ""));
            recordSession(threadId, principal, session, message, intent, helpResponse, false);
            return helpResponse;
        }

        Map<String, Object> copilot = copilotReply(message, threadId, body, principal);
        decorateFromIntent(copilot, intent);
        markTrace(trace, intent, "copilot", false, false);
        recordSession(threadId, principal, session, message, intent, copilot, false);
        return copilot;
    }

    /* ================================================================ *
     * Session state / trace helpers (P1/P2)
     * ================================================================ */

    private HelpSessionState loadSession(String threadId, AuthenticatedPrincipal principal) {
        if (helpSessionStateService == null || principal == null) {
            return null;
        }
        try {
            return helpSessionStateService.load(threadId, principal);
        } catch (Exception ex) {
            log.warn("[ChatService] help session load failed: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Multi-turn continuity: a follow-up such as "它为什么失败" can omit runId.
     * When the thread's last outcome was a run diagnosis, reuse that run so the
     * clarification gate is not re-triggered on every turn.
     */
    private void applySessionRunId(HelpIntent.Result intent, HelpSessionState session) {
        if (intent == null || session == null) {
            return;
        }
        String lastRunId = session.getLastRunId();
        if (lastRunId == null || lastRunId.trim().isEmpty()) {
            return;
        }
        if (!"RUN_DIAGNOSIS".equalsIgnoreCase(session.getLastDomain())) {
            return;
        }
        if (intent.getEntities() == null) {
            intent.setEntities(new HelpIntent.Entities());
        }
        if (intent.getEntities().getRunId() != null && !intent.getEntities().getRunId().trim().isEmpty()) {
            return;
        }
        intent.getEntities().setRunId(lastRunId.trim());
        if (intent.getRouteAction() == HelpIntent.RouteAction.CLARIFY) {
            intent.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);
            intent.setNeedsClarification(false);
            intent.setClarificationPrompt(null);
        }
    }

    private void recordSession(String threadId,
                               AuthenticatedPrincipal principal,
                               HelpSessionState session,
                               String message,
                               HelpIntent.Result intent,
                               Map<String, Object> response,
                               boolean pendingClarification) {
        if (helpSessionStateService == null || principal == null) {
            return;
        }
        try {
            HelpSessionState state = session != null ? session : HelpSessionState.forThread(threadId);
            if (state.getGoal() == null || state.getGoal().trim().isEmpty()) {
                state.setGoal(message);
            }
            String domain = intent != null && intent.getDomain() != null
                    ? intent.getDomain().name()
                    : text(response, "domain", null);
            String routeAction = intent != null && intent.getRouteAction() != null
                    ? intent.getRouteAction().name()
                    : text(response, "routeAction", null);
            String runId = text(response, "runId", null);
            if ((runId == null || runId.trim().isEmpty())
                    && intent != null && intent.getEntities() != null) {
                runId = intent.getEntities().getRunId();
            }
            helpSessionStateService.mergeAfterRoute(state, domain, routeAction, runId, pendingClarification);
            if (pendingClarification) {
                helpSessionStateService.mergeCheckpoint(state, "{\"pending\":\"run_id\"}");
            } else if (intent != null && intent.getDomain() == HelpIntent.Domain.RUN_DIAGNOSIS) {
                /* A successful diagnosis consumes the pending run-id checkpoint. */
                helpSessionStateService.mergeCheckpoint(state, "");
            }
            helpSessionStateService.save(state, principal);
        } catch (Exception ex) {
            /* Session state must never break a chat response. */
            log.warn("[ChatService] help session save failed: {}", ex.getClass().getSimpleName());
        }
    }

    private void markTrace(HelpTrace trace,
                           HelpIntent.Result intent,
                           String provider,
                           boolean cacheHit,
                           boolean escalated) {
        if (helpTraceService == null || trace == null) {
            return;
        }
        if (intent != null) {
            helpTraceService.markRoute(trace,
                    intent.getDomain() == null ? null : intent.getDomain().name(),
                    intent.getRouteAction() == null ? null : intent.getRouteAction().name());
        }
        markTrace(trace, null, null, provider, cacheHit, escalated);
    }

    private void markTrace(HelpTrace trace,
                           HelpIntent.Domain domain,
                           HelpIntent.RouteAction action,
                           String provider,
                           boolean cacheHit,
                           boolean escalated) {
        if (helpTraceService == null || trace == null) {
            return;
        }
        if (domain != null || action != null) {
            helpTraceService.markRoute(trace,
                    domain == null ? null : domain.name(),
                    action == null ? null : action.name());
        }
        if (provider != null && !provider.trim().isEmpty()) {
            helpTraceService.markProvider(trace, provider);
        }
        if (cacheHit) {
            helpTraceService.markCacheHit(trace);
        }
        if (escalated) {
            helpTraceService.markEscalated(trace);
        }
    }

    private void markRetrievalRounds(HelpTrace trace, Map<String, Object> response) {
        if (helpTraceService == null || trace == null || response == null) {
            return;
        }
        Object helpLoop = response.get("helpLoop");
        if (helpLoop instanceof Map) {
            Object rounds = ((Map<?, ?>) helpLoop).get("retrievalRounds");
            if (rounds instanceof Number) {
                helpTraceService.markRetrievalRounds(trace, ((Number) rounds).intValue());
            }
        }
    }

    /**
     * Roles are only ever taken from the authenticated principal. Anything the client puts in the
     * request body (roles / role) is ignored so a caller cannot self-elevate to admin.
     */
    private List<String> rolesOf(AuthenticatedPrincipal principal) {
        if (principal == null || principal.getRoles() == null) {
            return Collections.emptyList();
        }
        return principal.getRoles();
    }

    private void applyPreferredDomain(Map<String, Object> body, HelpIntent.Result intent) {
        String preferred = text(body, "preferredDomain", "");
        if (preferred.isEmpty() || intent == null) {
            return;
        }
        try {
            HelpIntent.Domain domain = HelpIntent.Domain.valueOf(preferred.trim().toUpperCase(Locale.ROOT));
            if (domain == HelpIntent.Domain.PRODUCT_HELP
                    && intent.getRouteAction() == HelpIntent.RouteAction.COPILOT) {
                intent.setDomain(domain);
                intent.setRouteAction(HelpIntent.RouteAction.PLAYBOOK);
            } else if (domain == HelpIntent.Domain.RUN_DIAGNOSIS
                    && intent.getRouteAction() == HelpIntent.RouteAction.COPILOT) {
                intent.setDomain(domain);
                if (intent.getEntities() == null || intent.getEntities().getRunId() == null) {
                    intent.setRouteAction(HelpIntent.RouteAction.CLARIFY);
                    intent.setNeedsClarification(true);
                    intent.setClarificationPrompt("请提供运行 ID（runId），或从「运行中心」打开对应 run 后再问我。");
                } else {
                    intent.setRouteAction(HelpIntent.RouteAction.HELP_GRAPH);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // ignore invalid preferredDomain
        }
    }

    /**
     * Resolves (and enforces) thread ownership.
     *
     * <ul>
     *   <li>new thread: created with the caller as owner;</li>
     *   <li>existing thread with a matching owner: reused;</li>
     *   <li>legacy thread with no owner rows: claimed by the caller;</li>
     *   <li>existing thread owned by somebody else: rejected.</li>
     * </ul>
     *
     * A null principal keeps the legacy unchecked behaviour for internal/test callers; every HTTP
     * path resolves a principal first.
     */
    private String resolveThreadId(Map<String, Object> body, String message, AuthenticatedPrincipal principal) {
        String existing = text(body, "threadId", "");
        if (existing.isEmpty()) {
            String threadId = UUID.randomUUID().toString();
            if (principal == null) {
                mapper.insertThread(threadId, title(message));
            } else {
                mapper.insertThreadOwned(threadId, title(message),
                        principal.getUserId(), principal.getTenantId());
            }
            return threadId;
        }
        if (principal == null) {
            return existing;
        }
        Map<String, Object> owner = mapper.findThreadOwner(existing);
        if (owner == null) {
            /* Client-proposed thread id that does not exist yet: create it for this caller. */
            mapper.insertThreadOwned(existing, title(message),
                    principal.getUserId(), principal.getTenantId());
            return existing;
        }
        String ownerUserId = ownerText(owner, "user_id", "userId");
        String ownerTenantId = ownerText(owner, "tenant_id", "tenantId");
        if (ownerUserId.isEmpty() && ownerTenantId.isEmpty()) {
            /* Legacy thread created before ownership tracking: claim it (待确认: first caller wins).
             * The claim is conditional, so a concurrent claimer cannot overwrite the winner. */
            if (mapper.updateThreadOwner(existing, principal.getUserId(), principal.getTenantId()) > 0) {
                return existing;
            }
            owner = mapper.findThreadOwner(existing);
            ownerUserId = ownerText(owner, "user_id", "userId");
            ownerTenantId = ownerText(owner, "tenant_id", "tenantId");
        }
        if (!sameOwner(ownerUserId, principal.getUserId()) || !sameOwner(ownerTenantId, principal.getTenantId())) {
            throw new IllegalArgumentException("thread belongs to another user");
        }
        return existing;
    }

    private static String ownerText(Map<String, Object> owner, String... keys) {
        if (owner == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = owner.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static boolean sameOwner(String ownerSide, String principalSide) {
        String left = ownerSide == null ? "" : ownerSide.trim();
        String right = principalSide == null ? "" : principalSide.trim();
        return left.equals(right);
    }

    private void persistAssistant(String threadId, String content) {
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "assistant",
                content == null ? "" : content);
    }

    private void decorateFromIntent(Map<String, Object> response, HelpIntent.Result intent) {
        if (response == null || intent == null) {
            return;
        }
        Map<String, Object> envelope = intent.toEnvelopeFields();
        for (Map.Entry<String, Object> entry : envelope.entrySet()) {
            if (!response.containsKey(entry.getKey()) || response.get(entry.getKey()) == null) {
                response.put(entry.getKey(), entry.getValue());
            }
        }
        if (!response.containsKey("provider") || response.get("provider") == null) {
            if (Boolean.TRUE.equals(response.get("routedToWorkflow"))) {
                response.put("provider", "workflow-router");
            } else {
                response.put("provider", "copilot");
            }
        }
    }

    private void decorateResearch(Map<String, Object> response, String workflowKey) {
        if (response == null) {
            return;
        }
        response.put("domain", HelpIntent.Domain.RESEARCH.name());
        response.put("complexity", HelpIntent.Complexity.L2.name());
        response.put("routeAction", HelpIntent.RouteAction.DISPATCH_WORKFLOW.name());
        response.put("provider", "workflow-router");
        if (workflowKey != null) {
            response.put("workflowKey", workflowKey);
        }
    }

    private void mergeDomainIfMissing(Map<String, Object> response,
                                      HelpIntent.Domain domain,
                                      HelpIntent.RouteAction action) {
        if (!response.containsKey("domain")) {
            response.put("domain", domain.name());
        }
        if (!response.containsKey("routeAction")) {
            response.put("routeAction", action.name());
        }
        if (!response.containsKey("complexity")) {
            response.put("complexity", HelpIntent.Complexity.L0.name());
        }
    }

    /* ================================================================ *
     * Active-run message queue (steer / follow_up)
     * ================================================================ */

    /** Default delivery for chat messages sent while the thread's run is RUNNING (待确认 with product). */
    static final String DEFAULT_ACTIVE_RUN_DELIVERY = WorkflowService.QUEUE_KIND_FOLLOW_UP;

    private static final Set<String> QUEUE_FALLBACK_CODES = new HashSet<String>(Arrays.asList(
            "NOT_FOUND", "RUN_NOT_RUNNING", "NOT_ACTIVE", "RUN_PAUSED", "DISABLED", "OWNER_MISMATCH",
            "ORCHESTRATOR_UNAVAILABLE", "ORCHESTRATOR_ERROR",
            /* parallel fan-out: every agent already finished its turn → start a normal turn */
            "NO_RECEIVER", "TARGET_FINISHED"));

    /**
     * Returns the chat response when the message was queued into (or rejected by) the active run,
     * or null to continue with normal routing. Recoverable failures (run finished, paused at
     * approval, orchestrator unavailable, ...) fall back to normal routing; hard rejections
     * (queue full, delivery limit, message too long) are reported without starting a new run.
     */
    private Map<String, Object> tryQueueIntoActiveRun(Map<String, Object> body,
                                                      AuthenticatedPrincipal principal,
                                                      HelpSessionState session,
                                                      String message,
                                                      String threadId,
                                                      HelpTrace trace) {
        if (principal == null || session == null || workflowService == null
                || langChainGateway == null || !langChainGateway.isEnabled()) {
            return null;
        }
        String requested = text(body, "delivery", "").trim();
        if ("new".equalsIgnoreCase(requested)) {
            return null;
        }
        String kind = WorkflowService.normalizeQueueKind(requested);
        if (kind == null) {
            /*
             * Implicit delivery only when this thread's last turn dispatched the run; a thread whose
             * last turn diagnosed some run keeps normal routing (e.g. "它为什么失败？").
             */
            if (!HelpIntent.RouteAction.DISPATCH_WORKFLOW.name().equals(session.getLastRouteAction())) {
                return null;
            }
            kind = DEFAULT_ACTIVE_RUN_DELIVERY;
        }
        String runId = session.getLastRunId();
        if (runId == null || runId.trim().isEmpty() || message == null || message.trim().isEmpty()) {
            return null;
        }
        Map<String, Object> result;
        try {
            result = workflowService.enqueueRunMessage(runId.trim(), principal, kind, message);
        } catch (Exception ex) {
            return null;
        }
        if (result == null) {
            return null;
        }
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        String code = text(result, "code", "");
        if (!ok && (code.isEmpty() || QUEUE_FALLBACK_CODES.contains(code))) {
            return null;
        }
        markTrace(trace, HelpIntent.Domain.RESEARCH, HelpIntent.RouteAction.DISPATCH_WORKFLOW,
                "agent-queue", false, false);
        String reply;
        if (ok) {
            reply = WorkflowService.QUEUE_KIND_STEER.equals(kind)
                    ? String.format("\u5df2\u5c06\u6d88\u606f\u4f5c\u4e3a\u5f15\u5bfc\u6307\u4ee4\u6392\u961f\u5230\u8fd0\u884c\u4e2d\u7684\u5de5\u4f5c\u6d41\uff08\u8fd0\u884c ID: %s\uff09\uff0c\u5c06\u5728\u5f53\u524d\u5de5\u5177\u8c03\u7528\u7ed3\u675f\u540e\u9001\u8fbe\u3002", runId)
                    : String.format("\u5df2\u5c06\u6d88\u606f\u4f5c\u4e3a\u540e\u7eed\u6d88\u606f\u6392\u961f\u5230\u8fd0\u884c\u4e2d\u7684\u5de5\u4f5c\u6d41\uff08\u8fd0\u884c ID: %s\uff09\uff0c\u5c06\u5728\u667a\u80fd\u4f53\u5b8c\u6210\u5f53\u524d\u56de\u7b54\u540e\u5904\u7406\u3002", runId);
        } else {
            reply = String.format("\u6d88\u606f\u672a\u80fd\u6392\u961f\u5230\u8fd0\u884c\u4e2d\u7684\u5de5\u4f5c\u6d41\uff08%s\uff09\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002", code);
        }
        persistAssistant(threadId, reply);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("threadId", threadId);
        response.put("message", reply);
        response.put("content", reply);
        response.put("ok", ok);
        response.put("queued", ok);
        response.put("queueKind", kind);
        if (ok) {
            Map<String, Object> queuedMessage = objectMap(result.get("message"));
            response.put("queueId", queuedMessage.get("id"));
            response.put("queuePosition", result.get("position"));
        } else {
            response.put("queueError", code);
        }
        response.put("runId", runId.trim());
        response.put("runStatus", "RUNNING");
        response.put("domain", HelpIntent.Domain.RESEARCH.name());
        response.put("routeAction", HelpIntent.RouteAction.DISPATCH_WORKFLOW.name());
        response.put("provider", "agent-queue");
        response.put("needsClarification", false);
        response.put("escalateHuman", false);
        response.put("cacheHit", false);
        return response;
    }

    /* ================================================================ *
     * Workflow dispatch
     * ================================================================ */

    private Map<String, Object> dispatchWorkflow(String workflowKey,
                                                  String message,
                                                  String threadId,
                                                  Map<String, Object> body,
                                                  AuthenticatedPrincipal principal,
                                                  HelpTrace trace) {
        markTrace(trace, HelpIntent.Domain.RESEARCH, HelpIntent.RouteAction.DISPATCH_WORKFLOW,
                "workflow-router", false, false);
        String ticker = tickerFrom(body, objectMap(body == null ? null : body.get("state")), message);
        String subject = ticker.isEmpty() ? message : ticker;

        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("message", message);
        if (!ticker.isEmpty()) {
            inputs.put("ticker", ticker);
            inputs.put("symbol", ticker);
        }
        inputs.putAll(trustedState(body, principal));
        Map<String, Object> pageContext = objectMap(body == null ? null : body.get("pageContext"));
        if (!pageContext.isEmpty()) {
            inputs.put("pageContext", pageContext);
        }

        WorkflowRun run;
        try {
            run = workflowService.createRun(workflowKey, subject, inputs, principal);
        } catch (Exception ex) {
            /* If workflow layout is not published or invalid, fall back to copilot */
            Map<String, Object> fallback = copilotReply(message, threadId, body, principal);
            fallback.put("domain", HelpIntent.Domain.RESEARCH.name());
            fallback.put("routeAction", HelpIntent.RouteAction.COPILOT.name());
            return fallback;
        }

        final String runId = run.getRunId();
        final Map<String, Object> safeInputs = new LinkedHashMap<>(inputs);
        if (workflowExecutor == null) {
            /* Only direct unit construction reaches this branch; Spring always injects the bounded pool. */
            workflowService.executeAsync(run, safeInputs, workflowKey);
        } else {
            workflowExecutor.execute(() -> workflowService.executeAsync(run, safeInputs, workflowKey));
        }

        String reply = String.format("\u5df2\u81ea\u52a8\u8def\u7531\u5230\u5de5\u4f5c\u6d41 [%s]\uff0c\u8fd0\u884c ID: %s\uff0c\u72b6\u6001: %s",
                workflowKey, runId, "RUNNING");
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "assistant", reply);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("threadId", threadId);
        response.put("message", reply);
        response.put("content", reply);
        response.put("workflowKey", workflowKey);
        response.put("runId", runId);
        response.put("runStatus", "RUNNING");
        response.put("routedToWorkflow", true);
        response.put("ok", true);
        response.put("domain", HelpIntent.Domain.RESEARCH.name());
        response.put("complexity", HelpIntent.Complexity.L2.name());
        response.put("routeAction", HelpIntent.RouteAction.DISPATCH_WORKFLOW.name());
        response.put("provider", "workflow-router");
        response.put("needsClarification", false);
        response.put("escalateHuman", false);
        response.put("cacheHit", false);
        return response;
    }

    /* ================================================================ *
     * Default copilot chat
     * ================================================================ */

    private Map<String, Object> copilotReply(String message, String threadId, Map<String, Object> body,
                                             AuthenticatedPrincipal principal) {
        AgentTemplate copilot = new AgentTemplate();
        copilot.setAgentId("aegis-alpha-copilot");
        copilot.setName("Aegis Alpha Copilot");
        copilot.setDescription("Conversational investment research assistant.");
        copilot.setCategory("copilot");
        copilot.setTags("chat,copilot,research");
        copilot.setPrompt(COPILOT_SYSTEM_PROMPT);
        // P4-6 (R5): body.modelName is client input; only allowlisted names are forwarded, others are
        // ignored (empty = the gateway default model) rather than trusted.
        String requestedModel = text(body, "modelName", "");
        copilot.setModelName(modelAllowlist.isAllowed(requestedModel) ? requestedModel.trim() : "");
        copilot.setToolsJson("[\"market-data\",\"portfolio\",\"news\",\"workflow\"]");

        Map<String, Object> state = trustedState(body, principal);
        state.put("message", message);
        String ticker = tickerFrom(body, state, message);
        if (!ticker.isEmpty()) {
            state.put("ticker", ticker);
            state.put("symbol", ticker);
            state.put("subject", ticker);
            if (!state.containsKey("marketDataOverview")) {
                state.put("marketDataOverview", marketOverview(ticker));
            }
        }
        Map<String, Object> node = copilotNode();
        Map<String, Object> result = langChainGateway.runAgent(copilot, state, node, ticker.isEmpty() ? "copilot chat" : ticker);
        String reply = text(result, "content", text(result, "message", ""));
        if (reply.trim().isEmpty()) {
            reply = "\u771f\u5b9e LLM \u672a\u8fd4\u56de\u5185\u5bb9\u3002";
            result.put("content", reply);
        }
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "assistant", reply);

        Map<String, Object> response = new LinkedHashMap<String, Object>(result);
        response.put("threadId", threadId);
        response.put("message", reply);
        response.put("content", reply);
        response.put("routedToWorkflow", false);
        response.put("ok", true);
        response.put("provider", "copilot");
        response.put("needsClarification", false);
        response.put("escalateHuman", false);
        response.put("cacheHit", false);
        return response;
    }

    static String loadCopilotPrompt() {
        java.io.InputStream in = ChatService.class.getClassLoader().getResourceAsStream(COPILOT_PROMPT_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + COPILOT_PROMPT_RESOURCE);
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read " + COPILOT_PROMPT_RESOURCE, ex);
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
                // nothing to do
            }
        }
    }

    /* ================================================================ *
     * Helpers
     * ================================================================ */

    private Map<String, Object> marketOverview(String ticker) {
        try {
            return marketDataService.overview(ticker);
        } catch (Exception ex) {
            Map<String, Object> unavailable = new LinkedHashMap<String, Object>();
            unavailable.put("ok", false);
            unavailable.put("symbol", ticker);
            unavailable.put("status", "unavailable");
            unavailable.put("error", ex.getMessage() == null ? String.valueOf(ex) : ex.getMessage());
            return unavailable;
        }
    }

    /**
     * Client {@code state} with every identity field removed. Identity (user / tenant / roles) is
     * re-applied only from the authenticated principal so a caller cannot impersonate another user
     * in workflow inputs, memory namespaces or agent prompts.
     */
    private Map<String, Object> trustedState(Map<String, Object> body, AuthenticatedPrincipal principal) {
        Map<String, Object> state = objectMap(body == null ? null : body.get("state"));
        for (String key : CLIENT_IDENTITY_KEYS) {
            state.remove(key);
        }
        if (principal != null) {
            if (principal.getUserId() != null) {
                state.put("userId", principal.getUserId());
            }
            if (principal.getTenantId() != null) {
                state.put("tenantId", principal.getTenantId());
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        return new LinkedHashMap<String, Object>();
    }

    static final String COPILOT_HANDLER = "general.agent";

    private Map<String, Object> copilotNode() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("label", "Aegis Alpha Copilot");
        data.put("nodeType", "agent");
        // P3-8: the copilot node handler is fixed; a client-supplied body.handler must not pick a
        // workflow handler (e.g. finance.stock_recommendation_aggregate) for free-form chat.
        data.put("handler", COPILOT_HANDLER);
        data.put("agentId", "aegis-alpha-copilot");
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("id", "aegis-alpha-copilot");
        node.put("type", "workflowNode");
        node.put("data", data);
        return node;
    }

    private static final Pattern A_SHARE_CODE_PATTERN = Pattern.compile("(\\d{6})(?:\\.(SZ|SH))?");

    private String tickerFrom(Map<String, Object> body, Map<String, Object> state, String message) {
        String direct = firstText(body, "ticker", "symbol", "code");
        if (!direct.isEmpty()) {
            return cleanTicker(direct);
        }
        String existing = firstText(state, "ticker", "symbol", "code");
        if (!existing.isEmpty()) {
            return cleanTicker(existing);
        }
        String msg = message == null ? "" : message;

        // Resolve Chinese names inside free-text (e.g. "分析一下生益科技" -> 600183.SH)
        String chineseResolved = marketDataService.resolveAShareSymbolPublic(msg);
        if (chineseResolved != null && !chineseResolved.trim().isEmpty()
                && !chineseResolved.trim().equals(msg.trim())) {
            String cleaned = cleanTicker(chineseResolved);
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }

        String normalized = marketDataService.normalizeSymbolPublic(msg);
        if (normalized != null && !normalized.isEmpty()) {
            return normalized;
        }

        Matcher aShareMatcher = A_SHARE_CODE_PATTERN.matcher(msg);
        if (aShareMatcher.find()) {
            String code = aShareMatcher.group(1);
            String suffix = aShareMatcher.group(2);
            if (suffix != null) {
                return code + "." + suffix;
            }
            if (code.startsWith("6")) return code + ".SH";
            return code + ".SZ";
        }

        Matcher matcher = TICKER_PATTERN.matcher(msg);
        while (matcher.find()) {
            String candidate = cleanTicker(matcher.group());
            if (!candidate.isEmpty() && !RoutingRules.isTickerStopword(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private String firstText(Map<String, Object> body, String... keys) {
        if (body == null) {
            return "";
        }
        for (String key : keys) {
            if (body.get(key) != null) {
                String value = String.valueOf(body.get(key)).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String cleanTicker(String value) {
        return value == null ? "" : value.trim().toUpperCase().replaceAll("[^A-Z0-9._-]", "");
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        if (body == null || body.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(body.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private String title(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "New Chat";
        }
        String clean = message.trim();
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }
}
