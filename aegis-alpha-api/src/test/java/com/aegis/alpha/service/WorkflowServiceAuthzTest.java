package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowNodeRun;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.domain.WorkflowRunEvent;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;

/**
 * P0 authorization tests for run ownership in {@link WorkflowService}.
 */
class WorkflowServiceAuthzTest {

    private WorkflowMapper mapper;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WorkflowMapper.class);
        service = new WorkflowService(
                mapper,
                mock(AgentMapper.class),
                new ObjectMapper(),
                mock(LangChainGateway.class),
                mock(CacheService.class),
                mock(BacktestService.class),
                mock(AgentTraceService.class),
                new WorkflowValidationService(),
                mock(TokenService.class),
                false);
        when(mapper.findRunEvents(anyString())).thenReturn(Collections.<WorkflowRunEvent>emptyList());
        when(mapper.findNodeRuns(anyString())).thenReturn(Collections.<WorkflowNodeRun>emptyList());
    }

    @Test
    void ownerCanReadOwnRun() {
        when(mapper.findRun("run-1")).thenReturn(run("run-1", "u-1", "tenant-1"));

        WorkflowRun result = service.runForPrincipal("run-1", principal("u-1", "tenant-1"));

        assertThat(result).isNotNull();
        assertThat(result.getRunId()).isEqualTo("run-1");
    }

    @Test
    void otherUserIsDenied() {
        when(mapper.findRun("run-1")).thenReturn(run("run-1", "u-1", "tenant-1"));

        assertThat(service.runForPrincipal("run-1", principal("u-2", "tenant-1"))).isNull();
    }

    @Test
    void otherTenantIsDenied() {
        when(mapper.findRun("run-1")).thenReturn(run("run-1", "u-1", "tenant-1"));

        assertThat(service.runForPrincipal("run-1", principal("u-1", "tenant-2"))).isNull();
    }

    @Test
    void unownedLegacyRunIsDeniedForNonAdmin() {
        when(mapper.findRun("legacy")).thenReturn(run("legacy", null, null));

        assertThat(service.runForPrincipal("legacy", principal("u-1", "tenant-1"))).isNull();
    }

    @Test
    void unownedLegacyRunIsVisibleToAdmin() {
        when(mapper.findRun("legacy")).thenReturn(run("legacy", null, null));

        WorkflowRun result = service.runForPrincipal("legacy", admin("u-admin", "tenant-1"));

        assertThat(result).isNotNull();
        assertThat(result.getRunId()).isEqualTo("legacy");
    }

    @Test
    void adminCanReadAnotherUsersRun() {
        when(mapper.findRun("run-1")).thenReturn(run("run-1", "u-1", "tenant-1"));

        assertThat(service.runForPrincipal("run-1", admin("u-admin", "tenant-9"))).isNotNull();
    }

    @Test
    void nullPrincipalIsDenied() {
        when(mapper.findRun("run-1")).thenReturn(run("run-1", "u-1", "tenant-1"));

        assertThat(service.runForPrincipal("run-1", null)).isNull();
        assertThat(service.runForPrincipal(null, principal("u-1", "tenant-1"))).isNull();
        assertThat(service.runForPrincipal("  ", principal("u-1", "tenant-1"))).isNull();
    }

    @Test
    void missingRunIsNull() {
        assertThat(service.runForPrincipal("nope", principal("u-1", "tenant-1"))).isNull();
    }

    @Test
    void nodeRunsAreOwnerScoped() {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setNodeId("market");
        when(mapper.findRun("run-1")).thenReturn(run("run-1", "u-1", "tenant-1"));
        when(mapper.findNodeRuns("run-1")).thenReturn(Arrays.asList(node));

        assertThat(service.nodeRunsForPrincipal("run-1", principal("u-1", "tenant-1"))).hasSize(1);
        assertThat(service.nodeRunsForPrincipal("run-1", principal("u-2", "tenant-1"))).isEmpty();
        assertThat(service.nodeRunsForPrincipal("run-1", null)).isEmpty();
    }

    @Test
    void runEventsAreOwnerScoped() {
        WorkflowRunEvent event = new WorkflowRunEvent();
        event.setEventType("RUN_CREATED");
        when(mapper.findRun("run-1")).thenReturn(run("run-1", "u-1", "tenant-1"));
        when(mapper.findRunEvents("run-1")).thenReturn(Arrays.asList(event));

        assertThat(service.runEventsForPrincipal("run-1", principal("u-1", "tenant-1"))).hasSize(1);
        assertThat(service.runEventsForPrincipal("run-1", principal("u-2", "tenant-1"))).isEmpty();
        assertThat(service.runEventsForPrincipal("run-1", null)).isEmpty();
    }

    @Test
    void createRunStampsTheOwner() {
        when(mapper.findDefinition("daily")).thenReturn(dailyDefinition());

        service.createRun("daily", "AAPL", new LinkedHashMap<String, Object>(), principal("u-1", "tenant-1"));

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(mapper).insertRun(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("u-1");
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-1");
    }

    @Test
    void createRunOverridesForgedUserIdInInputs() throws Exception {
        when(mapper.findDefinition("daily")).thenReturn(dailyDefinition());
        java.util.Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("userId", "victim");
        inputs.put("user_id", "victim");

        service.createRun("daily", "AAPL", inputs, principal("u-1", "tenant-1"));

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(mapper).insertRun(captor.capture());
        java.util.Map<?, ?> stored = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(captor.getValue().getInputsJson(), java.util.Map.class);
        assertThat(stored.get("userId")).isEqualTo("u-1");
        assertThat(stored.containsKey("user_id")).isFalse();
    }

    @Test
    void applyAuthenticatedUserIdAlwaysWins() {
        java.util.Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("userId", "victim");
        WorkflowService.applyAuthenticatedUserId(inputs, "u-1");
        assertThat(inputs.get("userId")).isEqualTo("u-1");
    }

    @Test
    void createRunWithoutPrincipalKeepsLegacyUnownedRun() {
        when(mapper.findDefinition("daily")).thenReturn(dailyDefinition());

        service.createRun("daily", "AAPL", new LinkedHashMap<String, Object>());

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(mapper).insertRun(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getTenantId()).isNull();
    }

    @Test
    void isRunAccessibleMatchesOwnerAndAdminRules() {
        WorkflowRun owned = run("run-1", "u-1", "tenant-1");

        assertThat(service.isRunAccessible(owned, principal("u-1", "tenant-1"))).isTrue();
        assertThat(service.isRunAccessible(owned, principal("u-2", "tenant-1"))).isFalse();
        assertThat(service.isRunAccessible(owned, null)).isFalse();
        assertThat(service.isRunAccessible(null, principal("u-1", "tenant-1"))).isFalse();
        assertThat(service.isRunAccessible(run("legacy", null, null), principal("u-1", "tenant-1"))).isFalse();
        assertThat(service.isRunAccessible(run("legacy", null, null), admin("root", "tenant-1"))).isTrue();
    }

    @Test
    void saveLayoutRejectsReadonlySystemWorkflow() {
        when(mapper.findDefinition("daily")).thenReturn(definition("daily", null, true));

        assertThatThrownBy(() -> service.saveLayoutForUser("u-1", "daily", new LinkedHashMap<String, Object>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Readonly");
        verify(mapper, never()).insertLayout(any());
        verify(mapper, never()).updateLayout(any());
    }

    @Test
    void saveLayoutRejectsAnotherUsersWorkflow() {
        when(mapper.findDefinition("mine")).thenReturn(definition("mine", "u-2", false));

        assertThatThrownBy(() -> service.saveLayoutForUser("u-1", "mine", new LinkedHashMap<String, Object>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another user");
        verify(mapper, never()).insertLayout(any());
        verify(mapper, never()).updateLayout(any());
    }

    @Test
    void publishVersionRejectsReadonlySystemWorkflow() {
        when(mapper.findDefinition("daily")).thenReturn(definition("daily", null, true));

        assertThatThrownBy(() -> service.publishVersion("daily", "u-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Readonly");
        verify(mapper, never()).insertVersion(any());
    }

    @Test
    void publishVersionRejectsAnotherUsersWorkflow() {
        when(mapper.findDefinition("mine")).thenReturn(definition("mine", "u-2", false));

        assertThatThrownBy(() -> service.publishVersion("mine", "u-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another user");
        verify(mapper, never()).insertVersion(any());
    }

    @Test
    void ownerCanSaveOwnWorkflowLayout() {
        when(mapper.findDefinition("mine")).thenReturn(definition("mine", "u-1", false));

        Map<String, Object> saved = service.saveLayoutForUser("u-1", "mine", validLayout("short prompt", null));

        assertThat(saved).isNotNull();
        verify(mapper, atLeastOnce()).findDefinition("mine");
        verify(mapper, times(1)).insertLayout(any());
    }

    @Test
    void createDefinitionStillWritesDefaultLayoutWithoutUserChecks() {
        // first lookup: key is free; afterwards the freshly inserted (owned, non-readonly) definition exists
        when(mapper.findDefinition(anyString())).thenReturn(null, definition("custom-workflow", "u-1", false));

        service.createDefinition("u-1", new LinkedHashMap<String, Object>());

        verify(mapper, times(1)).insertDefinition(any());
        verify(mapper, times(1)).insertLayout(any());
    }

    @Test
    void saveLayoutRejectsOverlongPrompt() {
        when(mapper.findDefinition("mine")).thenReturn(definition("mine", "u-1", false));
        StringBuilder longPrompt = new StringBuilder();
        for (int i = 0; i < 8001; i++) {
            longPrompt.append('p');
        }

        assertThatThrownBy(() -> service.saveLayoutForUser("u-1", "mine", validLayout(longPrompt.toString(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(WorkflowService.AccessDeniedException.class)
                .hasMessageContaining("prompt exceeds");
        verify(mapper, never()).insertLayout(any());
        verify(mapper, never()).updateLayout(any());
    }

    @Test
    void saveLayoutRejectsModelOutsideConfiguredWhitelist() {
        WorkflowValidationService validation = new WorkflowValidationService();
        validation.setAllowedModelNames("deepseek-v4-flash, deepseek-v4-pro");

        assertThatThrownBy(() -> validation.validateExecutableFields(validLayout("ok", "gpt-unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelName");
        validation.validateExecutableFields(validLayout("ok", "deepseek-v4-pro"));
        new WorkflowValidationService().validateExecutableFields(validLayout("ok", "anything"));
    }

    private static Map<String, Object> validLayout(String prompt, String modelName) {
        Map<String, Object> startData = new LinkedHashMap<String, Object>();
        startData.put("nodeType", "start");
        startData.put("handler", "start");
        Map<String, Object> start = new LinkedHashMap<String, Object>();
        start.put("id", "start");
        start.put("data", startData);
        Map<String, Object> agentData = new LinkedHashMap<String, Object>();
        agentData.put("handler", "general.agent");
        agentData.put("prompt", prompt);
        if (modelName != null) {
            agentData.put("modelName", modelName);
        }
        Map<String, Object> agent = new LinkedHashMap<String, Object>();
        agent.put("id", "agent");
        agent.put("data", agentData);
        Map<String, Object> edge = new LinkedHashMap<String, Object>();
        edge.put("source", "start");
        edge.put("target", "agent");
        Map<String, Object> layout = new LinkedHashMap<String, Object>();
        layout.put("nodes", java.util.Arrays.asList(start, agent));
        layout.put("edges", java.util.Collections.singletonList(edge));
        return layout;
    }

    private static WorkflowDefinition definition(String key, String owner, boolean readonly) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey(key);
        definition.setName(key);
        definition.setEngine("langgraph");
        definition.setOwnerUsername(owner);
        definition.setReadonlyFlag(readonly);
        return definition;
    }

    private static WorkflowDefinition dailyDefinition() {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey("daily");
        definition.setName("Daily");
        definition.setEngine("langgraph");
        return definition;
    }

    private static WorkflowRun run(String runId, String userId, String tenantId) {
        WorkflowRun run = new WorkflowRun();
        run.setRunId(runId);
        run.setWorkflowKey("daily");
        run.setStatus("COMPLETED");
        run.setUserId(userId);
        run.setTenantId(tenantId);
        return run;
    }

    private static AuthenticatedPrincipal principal(String userId, String tenantId) {
        return new AuthenticatedPrincipal(userId, userId, tenantId, Collections.<String>emptyList());
    }

    private static AuthenticatedPrincipal admin(String userId, String tenantId) {
        List<String> roles = new ArrayList<String>();
        roles.add("admin");
        return new AuthenticatedPrincipal(userId, userId, tenantId, roles);
    }
}
