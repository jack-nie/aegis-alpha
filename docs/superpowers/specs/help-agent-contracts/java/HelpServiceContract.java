package com.aegis.alpha.design.help;

import java.util.List;
import java.util.Map;

/**
 * Design contract for HelpService (Java owner of playbook / diagnosis / help_graph gateway).
 *
 * <pre>
 * ChatService.reply:
 *   intent = intentRouter.classifyExtended(message, pageContext)
 *   switch (intent.routeAction):
 *     DISPATCH_WORKFLOW -> dispatchWorkflow(...)
 *     PLAYBOOK          -> helpService.answerFromPlaybook(...)
 *     KNOWLEDGE_RAG     -> helpService.answerFromKnowledge(...)   // P1
 *     HELP_GRAPH        -> helpService.answerFromHelpGraph(...)   // P1; P0 diagnosis template ok
 *     CLARIFY           -> ChatMessageResponse.clarify(...)
 *     COPILOT           -> copilotReply(...)
 * </pre>
 */
public interface HelpServiceContract {

    /**
     * L0: keyword / id match against classpath or DB playbooks.
     * Returns null if no hit (caller may fall back to COPILOT).
     */
    ChatMessageDtos.ChatMessageResponse answerFromPlaybook(
            String threadId,
            String message,
            IntentModels.ExtendedIntentResult intent);

    /**
     * P1: Vanilla RAG via KnowledgeService with ACL pre-filter.
     */
    ChatMessageDtos.ChatMessageResponse answerFromKnowledge(
            String threadId,
            String message,
            IntentModels.ExtendedIntentResult intent,
            String authorization);

    /**
     * P0/P1: Run diagnosis. P0 may template from WorkflowService.getRun; P1 uses help_graph.
     */
    ChatMessageDtos.ChatMessageResponse diagnoseRun(
            String threadId,
            String message,
            IntentModels.ExtendedIntentResult intent,
            String authorization);

    /**
     * P1: Call orchestrator POST /help/execute with service token + optional delegated token.
     */
    ChatMessageDtos.ChatMessageResponse answerFromHelpGraph(
            String threadId,
            String message,
            IntentModels.ExtendedIntentResult intent,
            String authorization,
            Map<String, Object> pageContext);

    /**
     * Layer-1 cache lookup. Must no-op when intent.cacheable == false.
     */
    ChatMessageDtos.ChatMessageResponse cacheGet(String normalizedMessage, IntentModels.ExtendedIntentResult intent);

    /**
     * Layer-1 cache put. Must refuse when intent.cacheable == false or response has private refs.
     */
    void cachePut(String normalizedMessage, IntentModels.ExtendedIntentResult intent,
                  ChatMessageDtos.ChatMessageResponse response);

    /** Playbook catalog entry. */
    final class Playbook {
        public String id;
        public String title;
        public List<String> keywords;
        public String bodyMarkdown;
        public String deepLink;
    }
}
