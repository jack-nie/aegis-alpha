package com.aegis.alpha.service.help;

import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.LangChainGateway;
import com.aegis.alpha.service.WorkflowService;
import com.aegis.alpha.service.help.tool.HelpToolCatalog;
import com.aegis.alpha.service.help.tool.HelpToolGateway;
import com.aegis.alpha.service.help.tool.HelpToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Product help (playbook), hybrid lexical knowledge retrieval, help-loop, and run diagnosis for ChatService.
 */
@Service
public class HelpService {

    public static final String DEFAULT_DISCLAIMER =
            "根据现有资料无法确定完整答案。建议打开运行中心查看节点详情，或联系 Desk Lead / 平台支持。";

    private static final int MAX_PROBLEM_NODES = 8;
    private static final int MAX_OK_NODES = 6;
    private static final int MAX_EVENTS = 8;
    private static final int MAX_OUTPUT_SNIPPET = 160;

    private final PlaybookService playbookService;
    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final KnowledgeService knowledgeService;
    private final HelpLoopService helpLoopService;
    private final LangChainGateway langChainGateway;
    private final HelpToolGateway helpToolGateway;

    /** Off by default: remote /help/execute may only re-draft from Java-selected chunks. */
    @Value("${aegis.help.remote-generation-enabled:false}")
    private boolean remoteGenerationEnabled = false;

    @Autowired
    public HelpService(PlaybookService playbookService,
                       WorkflowService workflowService,
                       WorkflowMapper workflowMapper,
                       KnowledgeService knowledgeService,
                       HelpLoopService helpLoopService,
                       LangChainGateway langChainGateway,
                       HelpToolGateway helpToolGateway) {
        this.playbookService = playbookService;
        this.workflowService = workflowService;
        this.workflowMapper = workflowMapper;
        this.knowledgeService = knowledgeService;
        this.helpLoopService = helpLoopService;
        this.langChainGateway = langChainGateway;
        this.helpToolGateway = helpToolGateway;
    }

    /** Test helper when the LLM gateway is unused. */
    public HelpService(PlaybookService playbookService,
                       WorkflowService workflowService,
                       WorkflowMapper workflowMapper,
                       KnowledgeService knowledgeService,
                       HelpLoopService helpLoopService,
                       LangChainGateway langChainGateway) {
        this(playbookService, workflowService, workflowMapper, knowledgeService, helpLoopService,
                langChainGateway, null);
    }

    /** Test helper when both the LLM gateway and the tool gateway are unused. */
    public HelpService(PlaybookService playbookService,
                       WorkflowService workflowService,
                       WorkflowMapper workflowMapper,
                       KnowledgeService knowledgeService,
                       HelpLoopService helpLoopService) {
        this(playbookService, workflowService, workflowMapper, knowledgeService, helpLoopService,
                null, null);
    }

    public Map<String, Object> answerFromPlaybook(String threadId, String message, HelpIntent.Result intent) {
        PlaybookService.Playbook playbook = null;
        if (intent != null && intent.getPlaybookId() != null) {
            playbook = playbookService.findById(intent.getPlaybookId());
        }
        if (playbook == null) {
            playbook = playbookService.findBestMatch(message);
        }
        if (playbook == null) {
            return null;
        }
        String body = playbook.getBodyMarkdown();
        if (playbook.getDeepLink() != null && !playbook.getDeepLink().isEmpty()
                && body != null && !body.contains(playbook.getDeepLink())) {
            body = body + "\n\n相关入口：`" + playbook.getDeepLink() + "`";
        }
        return buildResponse(
                threadId,
                body,
                intent,
                "help-playbook",
                HelpIntent.RouteAction.PLAYBOOK,
                sourceRef("playbook", playbook.getId(), playbook.getTitle(), playbook.getDeepLink()),
                false,
                null
        );
    }

    /**
     * L1 knowledge answer: ACL pre-filtered public knowledge search + synthesized reply.
     * Returns null when no hits (caller may escalate to help loop or copilot).
     */
    public Map<String, Object> answerFromKnowledge(String threadId, String message, HelpIntent.Result intent,
                                                   List<String> principalRoles) {
        KnowledgeService.SearchResult search = knowledgeService.search(message, principalRoles, 5);
        if (search.getHits().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 文档检索回答\n\n");
        int i = 1;
        List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
        for (KnowledgeService.KnowledgeHit hit : search.getHits()) {
            if (i > 3) {
                break;
            }
            sb.append("### ").append(i++).append(". ").append(hit.getDoc().getTitle()).append("\n\n");
            sb.append(hit.getExcerpt()).append("\n\n");
            refs.add(sourceRef("chunk", hit.getDoc().getId(), hit.getDoc().getTitle(), null));
        }
        sb.append("_检索已应用 ACL 预过滤（visibility/role）；私有 run/组合不在知识索引中。_\n");

        Map<String, Object> response = buildResponse(
                threadId,
                sb.toString(),
                intent,
                "help-knowledge",
                HelpIntent.RouteAction.KNOWLEDGE_RAG,
                null,
                false,
                null
        );
        response.put("sourceRefs", refs);
        response.put("knowledge", search.toMap());
        response.put("ok", true);
        return response;
    }

    /**
     * L2 help loop. The local {@link HelpLoopService} is the single retrieval/grading implementation
     * (ACL pre-filtered, same corpus as knowledge answers), so sources do not depend on whether the
     * orchestrator is reachable. When {@code aegis.help.remote-generation-enabled} is on, the
     * orchestrator may only re-draft the answer from the chunks Java already selected; any remote
     * failure / {@code ok=false} / escalation keeps the local answer.
     */
    public Map<String, Object> answerFromHelpLoop(String threadId, String message, HelpIntent.Result intent,
                                                  List<String> principalRoles) {
        HelpLoopService.LoopResult loop = helpLoopService.run(message, principalRoles);
        Map<String, Object> response = buildResponse(
                threadId,
                loop.content,
                intent,
                loop.provider,
                HelpIntent.RouteAction.HELP_GRAPH,
                null,
                false,
                loop.disclaimer
        );
        response.put("ok", loop.ok);
        response.put("escalateHuman", loop.escalate);
        List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
        if (loop.chunks != null) {
            for (Map<String, Object> chunk : loop.chunks) {
                refs.add(sourceRef("chunk", String.valueOf(chunk.get("id")),
                        String.valueOf(chunk.get("title")), null));
            }
        }
        response.put("sourceRefs", refs);
        response.put("helpLoop", loop.toDiagnostics());

        if (remoteGenerationEnabled && langChainGateway != null && loop.ok
                && loop.chunks != null && !loop.chunks.isEmpty()) {
            Map<String, Object> remote = null;
            try {
                remote = langChainGateway.executeHelp(message, principalRoles,
                        intent == null ? null : intent.toEnvelopeFields(), loop.chunks);
            } catch (Exception ignored) {
                remote = null;
            }
            if (remote != null
                    && !Boolean.FALSE.equals(remote.get("ok"))
                    && !Boolean.TRUE.equals(remote.get("escalate"))
                    && !Boolean.TRUE.equals(remote.get("escalateHuman"))
                    && remote.get("content") != null
                    && !String.valueOf(remote.get("content")).trim().isEmpty()) {
                response.put("content", String.valueOf(remote.get("content")));
                response.put("message", String.valueOf(remote.get("content")));
                response.put("provider", "help-loop-orch");
            }
        }
        return response;
    }

    void setRemoteGenerationEnabled(boolean remoteGenerationEnabled) {
        this.remoteGenerationEnabled = remoteGenerationEnabled;
    }

    public Map<String, Object> clarify(String threadId, HelpIntent.Result intent) {
        String prompt = intent != null && intent.getClarificationPrompt() != null
                ? intent.getClarificationPrompt()
                : "请补充更多信息（例如 runId）以便继续。";
        Map<String, Object> response = buildResponse(
                threadId,
                prompt,
                intent,
                "help-clarify",
                HelpIntent.RouteAction.CLARIFY,
                null,
                false,
                null
        );
        response.put("needsClarification", true);
        response.put("clarificationPrompt", prompt);
        response.put("ok", true);
        return response;
    }

    /**
     * P1 diagnosis: run header + problem nodes first + recent events + suggested actions.
     */
    public Map<String, Object> diagnoseRun(String threadId, String message, HelpIntent.Result intent) {
        return diagnoseRun(threadId, message, intent, null);
    }

    /**
     * Owner-scoped diagnosis. With a principal the run, its nodes and its events are read through
     * the scoped workflow service, so a caller can only diagnose runs they own (or, for admins,
     * any run). A missing run and a run owned by somebody else produce the same generic answer:
     * the chat must not become a probe for other users' run ids, node names or event payloads.
     *
     * <p>A null principal keeps the legacy unscoped behaviour used by internal callers and tests.</p>
     */
    public Map<String, Object> diagnoseRun(String threadId, String message, HelpIntent.Result intent,
                                           AuthenticatedPrincipal principal) {
        String runId = intent != null && intent.getEntities() != null
                ? intent.getEntities().getRunId()
                : null;
        if (runId == null || runId.trim().isEmpty()) {
            return clarify(threadId, intent);
        }
        runId = runId.trim();

        if (principal != null) {
            WorkflowRun owned;
            try {
                owned = readRunTool(runId, principal);
            } catch (ToolUnavailableException ex) {
                return toolUnavailableDiagnosis(threadId, runId, intent, ex.getMessage());
            }
            if (owned == null) {
                return accessDeniedDiagnosis(threadId, runId, intent);
            }
            return buildDiagnosis(threadId, message, intent, owned,
                    readNodeRunsTool(runId, principal),
                    readRunEventsTool(runId, principal));
        }

        WorkflowRun run;
        try {
            run = workflowService.run(runId);
        } catch (Exception ex) {
            run = null;
        }
        if (run == null) {
            return runNotFoundDiagnosis(threadId, runId, intent);
        }
        return buildDiagnosis(threadId, message, intent, run, safeNodeRuns(runId), safeRunEvents(runId));
    }

    private Map<String, Object> buildDiagnosis(String threadId,
                                               String message,
                                               HelpIntent.Result intent,
                                               WorkflowRun run,
                                               List<WorkflowNodeRun> nodes,
                                               List<WorkflowRunEvent> events) {
        String status = run.getStatus() == null ? "UNKNOWN" : run.getStatus().toUpperCase(Locale.ROOT);
        List<WorkflowNodeRun> problemNodes = filterProblemNodes(nodes);
        List<String> suggestedActions = buildSuggestedActions(status, run, problemNodes, message);
        String focus = detectFocus(message, status);

        StringBuilder sb = new StringBuilder();
        sb.append("## 运行诊断\n\n");
        sb.append("### 概览\n\n");
        sb.append("- **runId**: `").append(run.getRunId()).append("`\n");
        sb.append("- **workflowKey**: `").append(nullToDash(run.getWorkflowKey())).append("`\n");
        sb.append("- **status**: `").append(status).append("`\n");
        sb.append("- **subject**: ").append(nullToDash(run.getSubject())).append("\n");
        if (run.getStartedAt() != null && !run.getStartedAt().trim().isEmpty()) {
            sb.append("- **startedAt**: ").append(run.getStartedAt().trim()).append("\n");
        }
        if (run.getCompletedAt() != null && !run.getCompletedAt().trim().isEmpty()) {
            sb.append("- **completedAt**: ").append(run.getCompletedAt().trim()).append("\n");
        }
        if (run.getErrorMessage() != null && !run.getErrorMessage().trim().isEmpty()) {
            sb.append("- **error**: ").append(run.getErrorMessage().trim()).append("\n");
        }
        if (run.getNodeCount() != null) {
            sb.append("- **nodeCount**: ").append(run.getNodeCount()).append("\n");
        }
        sb.append("- **focus**: ").append(focus).append("\n");
        sb.append("\n");

        sb.append("### 状态解读\n\n");
        sb.append(statusInterpretation(status, run, problemNodes)).append("\n\n");

        if (!problemNodes.isEmpty()) {
            sb.append("### 问题节点（优先处理）\n\n");
            int shown = 0;
            for (WorkflowNodeRun node : problemNodes) {
                if (shown >= MAX_PROBLEM_NODES) {
                    sb.append("- …另有 ").append(problemNodes.size() - shown).append(" 个问题节点，请打开运行中心查看\n");
                    break;
                }
                appendNodeLine(sb, node, true);
                shown++;
            }
            sb.append("\n");
        } else if (!nodes.isEmpty() && isTerminalFailure(status)) {
            sb.append("### 问题节点\n\n");
            sb.append("- 未找到标记为 FAILED/DEGRADED 的节点，但运行状态为 `")
                    .append(status)
                    .append("`。请查看运行级 error 与事件时间线。\n\n");
        }

        List<WorkflowNodeRun> okNodes = filterNonProblemNodes(nodes);
        if (!okNodes.isEmpty() && !problemNodes.isEmpty()) {
            sb.append("### 其他节点\n\n");
            int shown = 0;
            for (WorkflowNodeRun node : okNodes) {
                if (shown >= MAX_OK_NODES) {
                    sb.append("- …其余节点请打开运行中心查看\n");
                    break;
                }
                appendNodeLine(sb, node, false);
                shown++;
            }
            sb.append("\n");
        } else if (problemNodes.isEmpty() && !nodes.isEmpty()) {
            sb.append("### 节点摘要\n\n");
            int shown = 0;
            for (WorkflowNodeRun node : nodes) {
                if (shown >= MAX_OK_NODES + MAX_PROBLEM_NODES) {
                    sb.append("- …其余节点请打开运行中心查看\n");
                    break;
                }
                appendNodeLine(sb, node, false);
                shown++;
            }
            sb.append("\n");
        }

        List<WorkflowRunEvent> recentEvents = tailEvents(events, MAX_EVENTS);
        if (!recentEvents.isEmpty()) {
            sb.append("### 最近事件\n\n");
            for (WorkflowRunEvent event : recentEvents) {
                String type = firstNonEmpty(event.getEventType(), event.getStatus(), "EVENT");
                String when = firstNonEmpty(event.getCreatedAt(), "");
                String msg = firstNonEmpty(event.getMessage(), "");
                sb.append("- ");
                if (!when.isEmpty()) {
                    sb.append("`").append(when).append("` ");
                }
                sb.append("**").append(type).append("**");
                if (event.getNodeId() != null && !event.getNodeId().trim().isEmpty()) {
                    sb.append(" @ ").append(event.getNodeId().trim());
                }
                if (!msg.isEmpty()) {
                    sb.append(" — ").append(truncate(msg, MAX_OUTPUT_SNIPPET));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!suggestedActions.isEmpty()) {
            sb.append("### 建议下一步\n\n");
            int i = 1;
            for (String action : suggestedActions) {
                sb.append(i++).append(". ").append(action).append("\n");
            }
            sb.append("\n");
        }

        if (status.contains("DEGRAD")) {
            PlaybookService.Playbook degraded = playbookService.findById("degraded-meaning");
            if (degraded != null) {
                sb.append("相关说明：playbook **").append(degraded.getTitle()).append("**（`")
                        .append(degraded.getId()).append("`）\n\n");
            }
        }

        sb.append("详情：`/runs/").append(run.getRunId()).append("`\n");

        boolean escalate = shouldEscalate(status, run, problemNodes);
        String disclaimer = escalate ? DEFAULT_DISCLAIMER : null;
        if (disclaimer != null) {
            sb.append("\n").append(disclaimer);
        }

        List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
        refs.add(sourceRef("run", run.getRunId(), run.getWorkflowKey(), "/runs/" + run.getRunId()));
        if (status.contains("DEGRAD")) {
            refs.add(sourceRef("playbook", "degraded-meaning", "什么是 DEGRADED", "/runs"));
        }

        Map<String, Object> response = buildResponse(
                threadId,
                sb.toString(),
                intent,
                "help-diagnosis",
                HelpIntent.RouteAction.HELP_GRAPH,
                null,
                false,
                disclaimer
        );
        response.put("sourceRefs", refs);
        response.put("ok", !isHardFailure(status));
        response.put("escalateHuman", escalate);
        response.put("runId", run.getRunId());
        response.put("workflowKey", run.getWorkflowKey());
        response.put("runStatus", status);
        response.put("diagnosis", diagnosisMeta(
                focus,
                run.getRunId(),
                status,
                problemNodeSummaries(problemNodes),
                suggestedActions,
                escalate
        ));
        return response;
    }

    /**
     * Safety refusals for approve/trade style prompts (no side-effect tools).
     */
    public Map<String, Object> safetyRefuse(String threadId, String message, HelpIntent.Result intent) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String body;
        if (containsAny(lower, "批准", "approve", "驳回推荐", "reject recommendation")) {
            body = "我不能代为审批或驳回推荐。请打开 **推荐/审批收件箱**，核对证据后由人工操作。\n\n"
                    + "相关说明见 playbook：如何审批。入口：`/recommendations`";
        } else if (containsAny(lower, "下单", "买入 100", "卖出 100", "place order", "下单买入")) {
            body = "我不能执行下单或组合写入。交易与仓位调整请在组合界面由人工完成；"
                    + "如需研究结论，请描述标的并走研究工作流。";
        } else if (containsAny(lower, "别人的组合", "userid=", "user_id")) {
            body = "我不能查询其他用户的组合或私有数据。仅能在你的授权范围内只读查询。";
        } else if (containsAny(lower, "帮我下单", "执行交易", "trade now")) {
            body = "我不能执行下单或组合写入。交易与仓位调整请在组合界面由人工完成；"
                    + "如需研究结论，请描述标的并走研究工作流。";
        } else {
            return null;
        }
        Map<String, Object> response = buildResponse(
                threadId,
                body,
                intent,
                "help-safety",
                intent != null ? intent.getRouteAction() : HelpIntent.RouteAction.PLAYBOOK,
                sourceRef("playbook", "safety", "安全边界", null),
                false,
                null
        );
        response.put("ok", true);
        response.put("escalateHuman", false);
        return response;
    }

    private Map<String, Object> diagnosisMeta(
            String focus,
            String runId,
            String status,
            List<Map<String, Object>> problemNodes,
            List<String> suggestedActions,
            boolean escalate
    ) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("focus", focus);
        meta.put("runId", runId);
        meta.put("status", status);
        meta.put("problemNodes", problemNodes == null ? Collections.emptyList() : problemNodes);
        meta.put("suggestedActions", suggestedActions == null ? Collections.emptyList() : suggestedActions);
        meta.put("escalateHuman", escalate);
        return meta;
    }

    private List<Map<String, Object>> problemNodeSummaries(List<WorkflowNodeRun> problemNodes) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        if (problemNodes == null) {
            return list;
        }
        for (WorkflowNodeRun node : problemNodes) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("nodeId", node.getNodeId());
            row.put("nodeName", node.getNodeName());
            row.put("status", node.getStatus());
            row.put("errorMessage", node.getErrorMessage());
            list.add(row);
            if (list.size() >= MAX_PROBLEM_NODES) {
                break;
            }
        }
        return list;
    }

    private String detectFocus(String message, String status) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.contains("degrad") || lower.contains("降级")) {
            return "DEGRADED";
        }
        if (lower.contains("超时") || lower.contains("timeout")) {
            return "TIMEOUT";
        }
        if (lower.contains("失败") || lower.contains("failed") || lower.contains("error") || lower.contains("报错")) {
            return "FAILURE";
        }
        if (status != null && status.contains("DEGRAD")) {
            return "DEGRADED";
        }
        if (isHardFailure(status)) {
            return "FAILURE";
        }
        if ("RUNNING".equals(status) || "QUEUED".equals(status)) {
            return "IN_PROGRESS";
        }
        if ("COMPLETED".equals(status)) {
            return "COMPLETED";
        }
        return status == null ? "UNKNOWN" : status;
    }

    private String statusInterpretation(String status, WorkflowRun run, List<WorkflowNodeRun> problemNodes) {
        if (status.contains("DEGRAD")) {
            StringBuilder s = new StringBuilder();
            s.append("状态为 **DEGRADED**：工作流可能已产出结果，但证据不足或部分节点失败。");
            s.append("审批前请核对证据与问题节点；可参考 playbook「什么是 DEGRADED」。");
            if (!problemNodes.isEmpty()) {
                s.append(" 当前识别到 **").append(problemNodes.size()).append("** 个问题节点。");
            }
            return s.toString();
        }
        if ("FAILED".equals(status)) {
            return "运行 **FAILED**：未成功完成。请优先处理失败节点错误，修正输入或上游数据后重跑同一 published workflow。";
        }
        if ("CANCELLED".equals(status)) {
            return "运行 **CANCELLED**：已被取消。若非预期取消，请确认是否有人工中止或控制面 cancel 请求。";
        }
        if ("RUNNING".equals(status) || "QUEUED".equals(status)) {
            return "运行仍在进行中（`" + status + "`）。可稍后刷新运行中心，或等待 SSE/轮询完成后再诊断。";
        }
        if ("COMPLETED".equals(status)) {
            if (problemNodes.isEmpty()) {
                return "运行 **COMPLETED**，未发现 FAILED/DEGRADED 节点。可在运行中心查看节点摘要与最终结果。";
            }
            return "运行标记为 COMPLETED，但仍有问题节点痕迹，请人工核对输出是否可用。";
        }
        if (run.getErrorMessage() != null && !run.getErrorMessage().trim().isEmpty()) {
            return "运行状态 `" + status + "`，并带有错误信息，建议结合事件时间线排查。";
        }
        return "运行状态 `" + status + "`。请结合节点与事件进一步判断。";
    }

    private List<String> buildSuggestedActions(
            String status,
            WorkflowRun run,
            List<WorkflowNodeRun> problemNodes,
            String message
    ) {
        List<String> actions = new ArrayList<String>();
        String runPath = "/runs/" + run.getRunId();
        if ("RUNNING".equals(status) || "QUEUED".equals(status)) {
            actions.add("在运行中心等待完成：`" + runPath + "`");
            actions.add("若长时间无进展，检查 orchestrator / 节点超时配置");
            return actions;
        }
        if ("COMPLETED".equals(status) && problemNodes.isEmpty()) {
            actions.add("打开运行中心查看最终输出：`" + runPath + "`");
            actions.add("如需正式推荐，确认是否已进入审批收件箱");
            return actions;
        }
        if (status.contains("DEGRAD")) {
            actions.add("打开问题节点输出，核对缺失 evidence / claims：`" + runPath + "`");
            actions.add("确认 ticker/subject 与市场数据源是否可用后重跑 published workflow");
            actions.add("在证据不足时不要强行审批（见 playbook degraded-meaning）");
            return actions;
        }
        if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
            actions.add("阅读运行级 error 与失败节点 errorMessage：`" + runPath + "`");
            actions.add("修正输入（ticker、参数）或等待上游数据恢复后重跑");
            if (!problemNodes.isEmpty()) {
                String first = firstNonEmpty(problemNodes.get(0).getNodeName(), problemNodes.get(0).getNodeId(), "失败节点");
                actions.add("优先排查节点：**" + first + "**");
            }
            actions.add("若反复失败，联系 Desk Lead 并附带 runId");
            return actions;
        }
        actions.add("打开运行中心核对详情：`" + runPath + "`");
        if (message != null && message.toLowerCase(Locale.ROOT).contains("超时")) {
            actions.add("检查节点 timeoutMs 与 LLM/数据源延迟");
        }
        return actions;
    }

    private boolean shouldEscalate(String status, WorkflowRun run, List<WorkflowNodeRun> problemNodes) {
        if (isHardFailure(status)) {
            return true;
        }
        if (status != null && status.contains("DEGRAD")) {
            return true;
        }
        if (run.getErrorMessage() != null && !run.getErrorMessage().trim().isEmpty()) {
            return true;
        }
        return problemNodes != null && !problemNodes.isEmpty() && isTerminalFailure(status);
    }

    private static boolean isHardFailure(String status) {
        return "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private static boolean isTerminalFailure(String status) {
        return isHardFailure(status) || (status != null && status.contains("DEGRAD"));
    }

    private void appendNodeLine(StringBuilder sb, WorkflowNodeRun node, boolean includeSnippet) {
        String nodeStatus = node.getStatus() == null ? "?" : node.getStatus();
        String label = firstNonEmpty(node.getNodeName(), node.getNodeId(), "node");
        sb.append("- **").append(label).append("**: `").append(nodeStatus).append("`");
        if (node.getAttempt() != null && node.getMaxAttempts() != null) {
            sb.append(" (attempt ").append(node.getAttempt()).append("/").append(node.getMaxAttempts()).append(")");
        }
        if (node.getErrorMessage() != null && !node.getErrorMessage().trim().isEmpty()) {
            sb.append(" — ").append(truncate(node.getErrorMessage().trim(), MAX_OUTPUT_SNIPPET));
        } else if (includeSnippet) {
            String snippet = outputSnippet(node.getOutputJson());
            if (!snippet.isEmpty()) {
                sb.append(" — ").append(snippet);
            }
        }
        sb.append("\n");
    }

    private static String outputSnippet(String outputJson) {
        if (outputJson == null || outputJson.trim().isEmpty()) {
            return "";
        }
        String cleaned = outputJson.trim().replace('\n', ' ');
        // Prefer summary-like fields if present in simple JSON
        int summaryIdx = cleaned.toLowerCase(Locale.ROOT).indexOf("\"summary\"");
        if (summaryIdx >= 0) {
            int colon = cleaned.indexOf(':', summaryIdx);
            if (colon > 0 && colon + 1 < cleaned.length()) {
                String rest = cleaned.substring(colon + 1).trim();
                if (rest.startsWith("\"")) {
                    int end = rest.indexOf('"', 1);
                    if (end > 1) {
                        return truncate(rest.substring(1, end), MAX_OUTPUT_SNIPPET);
                    }
                }
            }
        }
        return truncate(cleaned, MAX_OUTPUT_SNIPPET);
    }

    private List<WorkflowNodeRun> filterProblemNodes(List<WorkflowNodeRun> nodes) {
        List<WorkflowNodeRun> problems = new ArrayList<WorkflowNodeRun>();
        if (nodes == null) {
            return problems;
        }
        for (WorkflowNodeRun node : nodes) {
            if (isProblemNode(node)) {
                problems.add(node);
            }
        }
        return problems;
    }

    private List<WorkflowNodeRun> filterNonProblemNodes(List<WorkflowNodeRun> nodes) {
        List<WorkflowNodeRun> ok = new ArrayList<WorkflowNodeRun>();
        if (nodes == null) {
            return ok;
        }
        for (WorkflowNodeRun node : nodes) {
            if (!isProblemNode(node)) {
                ok.add(node);
            }
        }
        return ok;
    }

    private static boolean isProblemNode(WorkflowNodeRun node) {
        if (node == null) {
            return false;
        }
        String st = node.getStatus() == null ? "" : node.getStatus().toUpperCase(Locale.ROOT);
        if (st.contains("FAIL") || st.contains("DEGRAD") || st.contains("ERROR") || st.contains("CANCEL")
                || st.contains("TIMEOUT")) {
            return true;
        }
        return node.getErrorMessage() != null && !node.getErrorMessage().trim().isEmpty();
    }

    private List<WorkflowRunEvent> tailEvents(List<WorkflowRunEvent> events, int max) {
        if (events == null || events.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }
        int from = Math.max(0, events.size() - max);
        return new ArrayList<WorkflowRunEvent>(events.subList(from, events.size()));
    }

    private List<WorkflowNodeRun> safeNodeRuns(String runId) {
        try {
            List<WorkflowNodeRun> nodes = workflowMapper.findNodeRuns(runId);
            return nodes == null ? new ArrayList<WorkflowNodeRun>() : nodes;
        } catch (Exception ex) {
            return new ArrayList<WorkflowNodeRun>();
        }
    }

    private List<WorkflowRunEvent> safeRunEvents(String runId) {
        try {
            List<WorkflowRunEvent> events = workflowMapper.findRunEvents(runId);
            return events == null ? new ArrayList<WorkflowRunEvent>() : events;
        } catch (Exception ex) {
            return new ArrayList<WorkflowRunEvent>();
        }
    }

    /** Scoped reads: empty when the principal may not read the run. */
    private List<WorkflowNodeRun> safeScopedNodeRuns(String runId, AuthenticatedPrincipal principal) {
        try {
            List<WorkflowNodeRun> nodes = workflowService.nodeRunsForPrincipal(runId, principal);
            return nodes == null ? new ArrayList<WorkflowNodeRun>() : nodes;
        } catch (Exception ex) {
            return new ArrayList<WorkflowNodeRun>();
        }
    }

    private List<WorkflowRunEvent> safeScopedRunEvents(String runId, AuthenticatedPrincipal principal) {
        try {
            List<WorkflowRunEvent> events = workflowService.runEventsForPrincipal(runId, principal);
            return events == null ? new ArrayList<WorkflowRunEvent>() : events;
        } catch (Exception ex) {
            return new ArrayList<WorkflowRunEvent>();
        }
    }

    /**
     * Reads a run through the unified read-only tool gateway when available.
     * Returns null for "missing or not authorized"; throws {@link ToolUnavailableException}
     * for timeout / circuit-open / transport failures so the answer can distinguish
     * "you cannot see this" from "the diagnosis backend is temporarily unavailable".
     */
    private WorkflowRun readRunTool(String runId, AuthenticatedPrincipal principal) {
        if (helpToolGateway == null) {
            return workflowService.runForPrincipal(runId, principal);
        }
        HelpToolResult result = helpToolGateway.execute(
                HelpToolCatalog.GET_WORKFLOW_RUN,
                principal.getUserId(),
                () -> {
                    WorkflowRun run = workflowService.runForPrincipal(runId, principal);
                    if (run == null) {
                        throw new IllegalArgumentException("run_not_found");
                    }
                    return Collections.<String, Object>singletonMap("run", run);
                });
        if (!result.isOk()) {
            String error = result.getError() == null ? "" : result.getError();
            if (error.contains("run_not_found")) {
                return null;
            }
            throw new ToolUnavailableException(result.isCircuitOpen() ? "circuit_open" : error);
        }
        Object run = result.getData() == null ? null : result.getData().get("run");
        return run instanceof WorkflowRun ? (WorkflowRun) run : null;
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowNodeRun> readNodeRunsTool(String runId, AuthenticatedPrincipal principal) {
        if (helpToolGateway == null) {
            return safeScopedNodeRuns(runId, principal);
        }
        HelpToolResult result = helpToolGateway.execute(
                HelpToolCatalog.GET_WORKFLOW_RUN_NODES,
                principal.getUserId(),
                () -> Collections.<String, Object>singletonMap(
                        "nodes", workflowService.nodeRunsForPrincipal(runId, principal)));
        if (!result.isOk()) {
            return new ArrayList<WorkflowNodeRun>();
        }
        Object nodes = result.getData() == null ? null : result.getData().get("nodes");
        return nodes instanceof List ? (List<WorkflowNodeRun>) nodes : new ArrayList<WorkflowNodeRun>();
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowRunEvent> readRunEventsTool(String runId, AuthenticatedPrincipal principal) {
        if (helpToolGateway == null) {
            return safeScopedRunEvents(runId, principal);
        }
        HelpToolResult result = helpToolGateway.execute(
                HelpToolCatalog.GET_WORKFLOW_RUN_EVENTS,
                principal.getUserId(),
                () -> Collections.<String, Object>singletonMap(
                        "events", workflowService.runEventsForPrincipal(runId, principal)));
        if (!result.isOk()) {
            return new ArrayList<WorkflowRunEvent>();
        }
        Object events = result.getData() == null ? null : result.getData().get("events");
        return events instanceof List ? (List<WorkflowRunEvent>) events : new ArrayList<WorkflowRunEvent>();
    }

    private Map<String, Object> toolUnavailableDiagnosis(String threadId, String runId,
                                                         HelpIntent.Result intent, String reason) {
        String body = "诊断服务暂时不可用，请稍后重试。若持续失败，请在运行中心查看节点详情或联系平台支持。";
        Map<String, Object> response = buildResponse(
                threadId,
                body,
                intent,
                "help-diagnosis",
                HelpIntent.RouteAction.HELP_GRAPH,
                null,
                false,
                DEFAULT_DISCLAIMER
        );
        response.put("ok", false);
        response.put("escalateHuman", true);
        response.put("runId", runId);
        response.put("diagnosis", diagnosisMeta("TOOL_UNAVAILABLE", runId, null, null,
                Collections.singletonList("稍后重试或打开运行中心"), true));
        response.put("toolError", reason == null ? "tool_unavailable" : reason);
        return response;
    }

    /** Internal signal for a read-only tool outage (timeout / circuit open / transport). */
    private static final class ToolUnavailableException extends RuntimeException {
        private ToolUnavailableException(String message) {
            super(message);
        }
    }

    /** Generic "not found" answer for callers that do not authenticate through a principal. */
    private Map<String, Object> runNotFoundDiagnosis(String threadId, String runId, HelpIntent.Result intent) {
        String body = "未找到运行 `" + runId + "`。请确认 runId 是否正确，或在运行中心刷新列表后再试。\n\n"
                + "路径提示：`/runs/" + runId + "`\n\n"
                + DEFAULT_DISCLAIMER;
        Map<String, Object> response = buildResponse(
                threadId,
                body,
                intent,
                "help-diagnosis",
                HelpIntent.RouteAction.HELP_GRAPH,
                sourceRef("run", runId, "missing-run", "/runs/" + runId),
                false,
                DEFAULT_DISCLAIMER
        );
        response.put("ok", false);
        response.put("escalateHuman", true);
        response.put("runId", runId);
        response.put("diagnosis", diagnosisMeta("RUN_NOT_FOUND", runId, null, null,
                Collections.singletonList("确认 runId 后重试"), true));
        return response;
    }

    /**
     * Generic answer for a run the caller cannot read. Deliberately identical for "does not exist"
     * and "belongs to someone else", and carries no run status, node names or event payloads.
     */
    private Map<String, Object> accessDeniedDiagnosis(String threadId, String runId, HelpIntent.Result intent) {
        String body = "未找到运行或无权访问该运行。";
        Map<String, Object> response = buildResponse(
                threadId,
                body,
                intent,
                "help-diagnosis",
                HelpIntent.RouteAction.HELP_GRAPH,
                null,
                false,
                DEFAULT_DISCLAIMER
        );
        response.put("ok", false);
        response.put("escalateHuman", true);
        response.put("runId", runId);
        response.put("diagnosis", diagnosisMeta("ACCESS_DENIED", runId, null, null,
                Collections.singletonList("确认 runId 后重试，或联系管理员确认是否有该运行的访问权限"), true));
        return response;
    }

    private Map<String, Object> buildResponse(
            String threadId,
            String content,
            HelpIntent.Result intent,
            String provider,
            HelpIntent.RouteAction routeAction,
            Map<String, Object> sourceRef,
            boolean cacheHit,
            String disclaimer
    ) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("threadId", threadId);
        response.put("content", content == null ? "" : content);
        response.put("message", content == null ? "" : content);
        response.put("ok", true);
        response.put("provider", provider);
        response.put("routedToWorkflow", false);
        response.put("cacheHit", cacheHit);
        response.put("escalateHuman", false);
        response.put("disclaimer", disclaimer);
        response.put("needsClarification", intent != null && intent.isNeedsClarification());
        response.put("clarificationPrompt", intent == null ? null : intent.getClarificationPrompt());

        if (intent != null) {
            response.putAll(intent.toEnvelopeFields());
        }
        if (routeAction != null) {
            response.put("routeAction", routeAction.name());
        }

        List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
        if (sourceRef != null) {
            refs.add(sourceRef);
        }
        response.put("sourceRefs", refs);
        return response;
    }

    private static Map<String, Object> sourceRef(String type, String id, String title, String path) {
        Map<String, Object> ref = new LinkedHashMap<String, Object>();
        ref.put("type", type);
        ref.put("id", id);
        ref.put("title", title);
        ref.put("path", path);
        return ref;
    }

    private static String nullToDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.length() <= max) {
            return v;
        }
        return v.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static boolean containsAny(String lower, String... hints) {
        for (String hint : hints) {
            if (hint != null && lower.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
