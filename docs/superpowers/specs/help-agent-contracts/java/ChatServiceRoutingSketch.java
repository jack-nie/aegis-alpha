package com.aegis.alpha.design.help;

import java.util.Map;

/**
 * Design sketch: how ChatService.reply should branch after extended intent.
 * Not production code — implement inside com.aegis.alpha.service.ChatService.
 */
public final class ChatServiceRoutingSketch {

    private ChatServiceRoutingSketch() {
    }

    /**
     * Pseudocode reference for P0 ChatService.reply.
     *
     * <pre>
     * String message = text(body, "message", "");
     * String threadId = resolveThreadId(body); // create or reuse with ownership check
     * PageContext page = parsePageContext(body.get("pageContext"));
     *
     * ExtendedIntentResult intent = intentRouterService.classifyExtended(message, page);
     * // explicit workflowKey override still wins for RESEARCH dispatch
     * if (explicitWorkflowKey present) force DISPATCH_WORKFLOW;
     *
     * if (intent.cacheable) {
     *   ChatMessageResponse cached = helpService.cacheGet(normalize(message), intent);
     *   if (cached != null) return cached.toMap();
     * }
     *
     * switch (intent.routeAction) {
     *   case DISPATCH_WORKFLOW:
     *     return dispatchWorkflow(intent.workflowKey, message, threadId, body);
     *   case CLARIFY:
     *     return ChatMessageResponse.clarify(threadId, intent.clarificationPrompt).toMap();
     *   case PLAYBOOK:
     *     ChatMessageResponse pb = helpService.answerFromPlaybook(threadId, message, intent);
     *     if (pb != null) {
     *       if (intent.cacheable) helpService.cachePut(...);
     *       return pb.toMap();
     *     }
     *     return copilotReply(...); // miss fallback
     *   case KNOWLEDGE_RAG: // P1; P0 fallback playbook/copilot
     *   case HELP_GRAPH:
     *     if (intent.domain == RUN_DIAGNOSIS) {
     *       return helpService.diagnoseRun(threadId, message, intent, authorization).toMap();
     *     }
     *     return helpService.answerFromHelpGraph(...).toMap(); // P1
     *   case COPILOT:
     *   default:
     *     return copilotReply(message, threadId, body);
     * }
     * </pre>
     */
    public static Map<String, Object> replySketch() {
        throw new UnsupportedOperationException("design sketch only");
    }
}
