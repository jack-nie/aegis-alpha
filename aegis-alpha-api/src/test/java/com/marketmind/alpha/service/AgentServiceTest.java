package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketmind.alpha.domain.AgentTemplate;
import com.marketmind.alpha.mapper.AgentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceTest {
    private AgentMapper mapper;
    private LangChainGateway langChainGateway;
    private AgentService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentMapper.class);
        langChainGateway = mock(LangChainGateway.class);
        service = new AgentService(mapper, langChainGateway);
    }

    @Test
    void findByIdThrowsWhenNotFound() {
        when(mapper.findById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.findById("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void findByIdReturnsAgent() {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId("a1");
        when(mapper.findById("a1")).thenReturn(agent);

        assertThat(service.findById("a1")).isSameAs(agent);
    }

    @Test
    void createWithDefaults() {
        when(mapper.nextSortOrder()).thenReturn(1);

        AgentTemplate result = service.create("alice", "MyAgent");

        assertThat(result.getName()).isEqualTo("MyAgent");
        assertThat(result.getOwnerUsername()).isEqualTo("alice");
        assertThat(result.getStatus()).isEqualTo("IDLE");
        assertThat(result.isSystemPreset()).isFalse();
        assertThat(result.isReadonlyFlag()).isFalse();
        verify(mapper).insert(any(AgentTemplate.class));
    }

    @Test
    void createWithMapSetsDefaults() {
        when(mapper.nextSortOrder()).thenReturn(2);

        Map<String, Object> body = new HashMap<>();
        AgentTemplate result = service.create("bob", body);

        assertThat(result.getName()).isEqualTo("New Agent");
        assertThat(result.getModelName()).isEqualTo("deepseek-v4-flash");
        verify(mapper).insert(any(AgentTemplate.class));
    }

    @Test
    void updateThrowsForReadonlyAgent() {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId("ro-agent");
        agent.setReadonlyFlag(true);
        agent.setOwnerUsername("alice");
        when(mapper.findById("ro-agent")).thenReturn(agent);

        assertThatThrownBy(() -> service.update("alice", "ro-agent", new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Readonly");
    }

    @Test
    void updateThrowsForWrongOwner() {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId("a1");
        agent.setReadonlyFlag(false);
        agent.setOwnerUsername("alice");
        when(mapper.findById("a1")).thenReturn(agent);

        assertThatThrownBy(() -> service.update("bob", "a1", new HashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another user");
    }

    @Test
    void updateAppliesFields() {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId("a1");
        agent.setReadonlyFlag(false);
        agent.setOwnerUsername("alice");
        agent.setName("Old");
        agent.setDescription("Old desc");
        when(mapper.findById("a1")).thenReturn(agent);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "New");
        service.update("alice", "a1", body);

        verify(mapper).update(any(AgentTemplate.class));
    }

    @Test
    void copyClonesAgent() {
        AgentTemplate source = new AgentTemplate();
        source.setAgentId("src-1");
        source.setName("Original");
        source.setPrompt("test prompt");
        source.setReadonlyFlag(true);
        source.setModelName("deepseek-v4-flash");
        when(mapper.findById("src-1")).thenReturn(source);
        when(mapper.countByOwnerAndName("alice", "Original")).thenReturn(0);
        when(mapper.nextSortOrder()).thenReturn(5);

        AgentTemplate copy = service.copy("alice", "src-1");

        assertThat(copy.getName()).isEqualTo("Original");
        assertThat(copy.getPrompt()).isEqualTo("test prompt");
        assertThat(copy.isReadonlyFlag()).isFalse();
        assertThat(copy.isSystemPreset()).isFalse();
        verify(mapper).insert(any(AgentTemplate.class));
    }

    @Test
    void copyAppendsCopySuffixOnDuplicateName() {
        AgentTemplate source = new AgentTemplate();
        source.setAgentId("src-1");
        source.setName("Original");
        when(mapper.findById("src-1")).thenReturn(source);
        when(mapper.countByOwnerAndName("alice", "Original")).thenReturn(1);
        when(mapper.nextSortOrder()).thenReturn(6);

        AgentTemplate copy = service.copy("alice", "src-1");

        assertThat(copy.getName()).isEqualTo("Original Copy");
    }

    @Test
    void deleteThrowsForReadonly() {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId("ro-agent");
        agent.setReadonlyFlag(true);
        when(mapper.findById("ro-agent")).thenReturn(agent);

        assertThatThrownBy(() -> service.delete("alice", "ro-agent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteThrowsForWrongOwner() {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId("a1");
        agent.setReadonlyFlag(false);
        agent.setOwnerUsername("alice");
        when(mapper.findById("a1")).thenReturn(agent);

        assertThatThrownBy(() -> service.delete("bob", "a1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteSucceedsForOwnedEditableAgent() {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId("a1");
        agent.setReadonlyFlag(false);
        agent.setOwnerUsername("alice");
        when(mapper.findById("a1")).thenReturn(agent);
        when(mapper.deleteEditable("a1")).thenReturn(1);

        service.delete("alice", "a1");

        verify(mapper).deleteEditable("a1");
    }

    @Test
    void runDelegatesToLangChainGateway() {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId("a1");
        agent.setName("Test");
        when(mapper.findById("a1")).thenReturn(agent);
        Map<String, Object> expected = new HashMap<>();
        expected.put("summary", "done");
        when(langChainGateway.runAgent(any(), any(), any(), any())).thenReturn(expected);

        Map<String, Object> body = new HashMap<>();
        Map<String, Object> state = new HashMap<>();
        state.put("ticker", "AAPL");
        body.put("state", state);
        body.put("subject", "test");

        Map<String, Object> result = service.run("alice", "a1", body);

        assertThat(result).containsEntry("summary", "done");
        verify(mapper).updateRunState(any(AgentTemplate.class));
    }
}