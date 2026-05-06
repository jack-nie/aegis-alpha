package com.marketmind.alpha.service;

import com.marketmind.alpha.domain.AgentTemplate;
import com.marketmind.alpha.mapper.ChatMapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatService {
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}(?:[._-][A-Z])?\\b");
    private static final Set<String> SYMBOL_STOPWORDS = new HashSet<String>(Arrays.asList(
            "AI", "API", "CPI", "ETF", "GDP", "IPO", "LLM", "SEC", "USA", "USD", "CEO", "CFO", "ESG"
    ));

    private final ChatMapper mapper;
    private final LangChainGateway langChainGateway;
    private final MarketDataService marketDataService;

    public ChatService(ChatMapper mapper, LangChainGateway langChainGateway, MarketDataService marketDataService) {
        this.mapper = mapper;
        this.langChainGateway = langChainGateway;
        this.marketDataService = marketDataService;
    }

    public Object threads() {
        return mapper.threads();
    }

    public Map<String, Object> reply(Map<String, Object> body) {
        String message = text(body, "message", "");
        String threadId = UUID.randomUUID().toString();
        mapper.insertThread(threadId, title(message));
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "user", message);

        AgentTemplate copilot = new AgentTemplate();
        copilot.setAgentId("aegis-alpha-copilot");
        copilot.setName("Aegis Alpha Copilot");
        copilot.setDescription("Conversational investment research assistant.");
        copilot.setCategory("copilot");
        copilot.setTags("chat,copilot,research");
        copilot.setPrompt("你是 Aegis Alpha Copilot。请用中文回答用户的投资研究问题；如果实时行情、组合、新闻等上下文不足，请明确说明缺少哪些数据，不要编造。");
        copilot.setModelName(text(body, "modelName", ""));
        copilot.setToolsJson("[\"market-data\",\"portfolio\",\"news\",\"workflow\"]");

        Map<String, Object> state = objectMap(body == null ? null : body.get("state"));
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
        Map<String, Object> node = copilotNode(body);
        Map<String, Object> result = langChainGateway.runAgent(copilot, state, node, ticker.isEmpty() ? "copilot chat" : ticker);
        String reply = text(result, "content", text(result, "message", ""));
        if (reply.trim().isEmpty()) {
            reply = "真实 LLM 未返回内容。";
            result.put("content", reply);
        }
        mapper.insertMessage(UUID.randomUUID().toString(), threadId, "assistant", reply);

        Map<String, Object> response = new LinkedHashMap<String, Object>(result);
        response.put("threadId", threadId);
        response.put("message", reply);
        response.put("content", reply);
        return response;
    }

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        }
        return new LinkedHashMap<String, Object>();
    }

    private Map<String, Object> copilotNode(Map<String, Object> body) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("label", "Aegis Alpha Copilot");
        data.put("nodeType", "agent");
        data.put("handler", text(body, "handler", "general.agent"));
        data.put("agentId", "aegis-alpha-copilot");
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("id", "aegis-alpha-copilot");
        node.put("type", "workflowNode");
        node.put("data", data);
        return node;
    }

    private String tickerFrom(Map<String, Object> body, Map<String, Object> state, String message) {
        String direct = firstText(body, "ticker", "symbol", "code");
        if (!direct.isEmpty()) {
            return cleanTicker(direct);
        }
        String existing = firstText(state, "ticker", "symbol", "code");
        if (!existing.isEmpty()) {
            return cleanTicker(existing);
        }
        Matcher matcher = TICKER_PATTERN.matcher(message == null ? "" : message);
        while (matcher.find()) {
            String candidate = cleanTicker(matcher.group());
            if (!candidate.isEmpty() && !SYMBOL_STOPWORDS.contains(candidate)) {
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
