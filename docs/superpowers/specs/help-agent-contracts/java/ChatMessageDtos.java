package com.aegis.alpha.design.help;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Design DTOs for POST /_backend/chat/messages request/response envelope.
 */
public final class ChatMessageDtos {

    private ChatMessageDtos() {
    }

    public static final class ChatMessageRequest {
        public String message;
        public String threadId;
        public String workflowKey;
        public String modelName;
        /** Optional force: RESEARCH | PRODUCT_HELP | RUN_DIAGNOSIS | CHITCHAT */
        public String preferredDomain;
        public Map<String, Object> state = new LinkedHashMap<String, Object>();
        public IntentModels.PageContext pageContext = new IntentModels.PageContext();
    }

    public static final class SourceRef {
        public String type;
        public String id;
        public String title;
        public String path;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("type", type);
            m.put("id", id);
            m.put("title", title);
            m.put("path", path);
            return m;
        }
    }

    public static final class ChatMessageResponse {
        public String threadId;
        public String content;
        public String message;
        public boolean ok = true;
        public String domain;
        public String complexity;
        public String routeAction;
        public String provider;
        public boolean routedToWorkflow;
        public String runId;
        public String workflowKey;
        public String runStatus;
        public boolean needsClarification;
        public String clarificationPrompt;
        public boolean escalateHuman;
        public List<SourceRef> sourceRefs = new ArrayList<SourceRef>();
        public boolean cacheHit;
        public String disclaimer;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("threadId", threadId);
            m.put("content", content);
            m.put("message", message);
            m.put("ok", ok);
            m.put("domain", domain);
            m.put("complexity", complexity);
            m.put("routeAction", routeAction);
            m.put("provider", provider);
            m.put("routedToWorkflow", routedToWorkflow);
            m.put("runId", runId);
            m.put("workflowKey", workflowKey);
            m.put("runStatus", runStatus);
            m.put("needsClarification", needsClarification);
            m.put("clarificationPrompt", clarificationPrompt);
            m.put("escalateHuman", escalateHuman);
            List<Map<String, Object>> refs = new ArrayList<Map<String, Object>>();
            if (sourceRefs != null) {
                for (SourceRef r : sourceRefs) {
                    refs.add(r.toMap());
                }
            }
            m.put("sourceRefs", refs);
            m.put("cacheHit", cacheHit);
            m.put("disclaimer", disclaimer);
            return m;
        }

        public static ChatMessageResponse clarify(String threadId, String prompt) {
            ChatMessageResponse r = new ChatMessageResponse();
            r.threadId = threadId;
            r.ok = true;
            r.domain = IntentModels.Domain.RUN_DIAGNOSIS.name();
            r.complexity = IntentModels.Complexity.L2.name();
            r.routeAction = IntentModels.RouteAction.CLARIFY.name();
            r.provider = "help-clarify";
            r.needsClarification = true;
            r.clarificationPrompt = prompt;
            r.content = prompt;
            r.message = prompt;
            return r;
        }

        public static ChatMessageResponse playbook(
                String threadId, String body, String playbookId, String title) {
            ChatMessageResponse r = new ChatMessageResponse();
            r.threadId = threadId;
            r.ok = true;
            r.domain = IntentModels.Domain.PRODUCT_HELP.name();
            r.complexity = IntentModels.Complexity.L0.name();
            r.routeAction = IntentModels.RouteAction.PLAYBOOK.name();
            r.provider = "help-playbook";
            r.content = body;
            r.message = body;
            SourceRef ref = new SourceRef();
            ref.type = "playbook";
            ref.id = playbookId;
            ref.title = title;
            r.sourceRefs.add(ref);
            r.cacheHit = false;
            return r;
        }
    }
}
