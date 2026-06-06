package com.aegis.alpha.service;

import com.aegis.alpha.domain.AgentTemplate;
import com.aegis.alpha.mapper.AgentMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentService {
    private final AgentMapper mapper;
    private final LangChainGateway langChainGateway;

    public AgentService(AgentMapper mapper, LangChainGateway langChainGateway) {
        this.mapper = mapper;
        this.langChainGateway = langChainGateway;
    }

    public List<AgentTemplate> findAll() {
        return mapper.findAll();
    }

    public AgentTemplate findById(String agentId) {
        AgentTemplate agent = mapper.findById(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }
        return agent;
    }

    public AgentTemplate create(String username, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        return create(username, body);
    }

    public AgentTemplate create(String username, Map<String, Object> body) {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId(UUID.randomUUID().toString());
        agent.setName(text(body, "name", "New Agent"));
        agent.setDescription(text(body, "description", "Custom AI agent for research workflows."));
        agent.setCategory(text(body, "category", "analyst"));
        agent.setTags(text(body, "tags", "analyst,custom"));
        agent.setPrompt(text(body, "prompt", "You are an Aegis Alpha research agent. Return concise structured output."));
        agent.setModelName(text(body, "modelName", "deepseek-v4-flash"));
        agent.setToolsJson(text(body, "toolsJson", "[\"market-data\",\"portfolio\",\"news\"]"));
        agent.setStatus("IDLE");
        agent.setScheduleCron(text(body, "scheduleCron", ""));
        agent.setInputCount(number(body, "inputCount", 1));
        agent.setOutputCount(number(body, "outputCount", 1));
        agent.setToolCount(number(body, "toolCount", 3));
        agent.setSystemPreset(false);
        agent.setReadonlyFlag(false);
        agent.setOwnerUsername(username);
        agent.setSortOrder(mapper.nextSortOrder());
        agent.setUpdatedAt(now());
        mapper.insert(agent);
        return agent;
    }

    public AgentTemplate update(String username, String agentId, Map<String, Object> body) {
        AgentTemplate agent = findById(agentId);
        if (agent.isReadonlyFlag()) {
            throw new IllegalArgumentException("Readonly preset agents must be copied before editing.");
        }
        if (agent.getOwnerUsername() != null && !agent.getOwnerUsername().equals(username)) {
            throw new IllegalArgumentException("Agent belongs to another user.");
        }
        agent.setName(text(body, "name", agent.getName()));
        agent.setDescription(text(body, "description", agent.getDescription()));
        agent.setCategory(text(body, "category", agent.getCategory()));
        agent.setTags(text(body, "tags", agent.getTags()));
        agent.setPrompt(text(body, "prompt", agent.getPrompt()));
        agent.setModelName(text(body, "modelName", agent.getModelName()));
        agent.setToolsJson(text(body, "toolsJson", agent.getToolsJson()));
        agent.setStatus(text(body, "status", agent.getStatus() == null ? "IDLE" : agent.getStatus()));
        agent.setScheduleCron(text(body, "scheduleCron", agent.getScheduleCron()));
        agent.setInputCount(number(body, "inputCount", agent.getInputCount()));
        agent.setOutputCount(number(body, "outputCount", agent.getOutputCount()));
        agent.setToolCount(number(body, "toolCount", agent.getToolCount()));
        agent.setUpdatedAt(now());
        mapper.update(agent);
        return findById(agentId);
    }

    public AgentTemplate copy(String username, String agentId) {
        AgentTemplate source = findById(agentId);
        String name = source.getName();
        if (mapper.countByOwnerAndName(username, name) > 0) {
            name = name + " Copy";
        }
        AgentTemplate clone = new AgentTemplate();
        clone.setAgentId(UUID.randomUUID().toString());
        clone.setName(name);
        clone.setDescription(source.getDescription());
        clone.setCategory(source.getCategory());
        clone.setTags(source.getTags());
        clone.setPrompt(source.getPrompt());
        clone.setModelName(source.getModelName());
        clone.setToolsJson(source.getToolsJson());
        clone.setStatus("IDLE");
        clone.setScheduleCron(source.getScheduleCron());
        clone.setInputCount(source.getInputCount());
        clone.setOutputCount(source.getOutputCount());
        clone.setToolCount(source.getToolCount());
        clone.setSystemPreset(false);
        clone.setReadonlyFlag(false);
        clone.setOwnerUsername(username);
        clone.setSortOrder(mapper.nextSortOrder());
        clone.setUpdatedAt(now());
        mapper.insert(clone);
        return clone;
    }

    public Map<String, Object> run(String username, String agentId, Map<String, Object> body) {
        AgentTemplate agent = findById(agentId);
        Map<String, Object> state = objectMap(body == null ? null : body.get("state"));
        String subject = text(body, "subject", "manual agent run");
        Map<String, Object> result = langChainGateway.runAgent(agent, state, new LinkedHashMap<String, Object>(), subject);
        agent.setStatus("IDLE");
        agent.setLastRunAt(now());
        agent.setUpdatedAt(now());
        mapper.updateRunState(agent);
        return result;
    }

    public void delete(String username, String agentId) {
        AgentTemplate agent = findById(agentId);
        if (agent.isReadonlyFlag()) {
            throw new IllegalArgumentException("Readonly preset agents cannot be deleted.");
        }
        if (agent.getOwnerUsername() != null && !agent.getOwnerUsername().equals(username)) {
            throw new IllegalArgumentException("Agent belongs to another user.");
        }
        mapper.deleteEditable(agentId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        return new LinkedHashMap<>();
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        if (body == null || !body.containsKey(key) || body.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(body.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private int number(Map<String, Object> body, String key, int fallback) {
        if (body == null || body.get(key) == null) {
            return fallback;
        }
        Object value = body.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
