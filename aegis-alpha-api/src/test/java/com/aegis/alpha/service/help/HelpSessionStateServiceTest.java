package com.aegis.alpha.service.help;

import com.aegis.alpha.domain.HelpSessionState;
import com.aegis.alpha.mapper.HelpSessionMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class HelpSessionStateServiceTest {

    private static final AuthenticatedPrincipal ALICE =
            new AuthenticatedPrincipal("u-alice", "alice", "tenant-a", Collections.singletonList("ANALYST"));
    private static final AuthenticatedPrincipal BOB =
            new AuthenticatedPrincipal("u-bob", "bob", "tenant-a", Collections.singletonList("ANALYST"));
    private static final AuthenticatedPrincipal CAROL =
            new AuthenticatedPrincipal("u-carol", "carol", "tenant-b", Collections.singletonList("ANALYST"));

    private HelpSessionMapper mapper;
    private HelpSessionStateService service;

    @BeforeEach
    void setUp() {
        mapper = mock(HelpSessionMapper.class);
        service = new HelpSessionStateService(mapper);
    }

    @Test
    void saveSetsOwnerAndUpserts() {
        HelpSessionState state = new HelpSessionState();
        state.setThreadId("t-1");
        state.setGoal("查看 600519 的持仓风险");

        HelpSessionState saved = service.save(state, ALICE);

        assertThat(saved).isSameAs(state);
        assertThat(saved.getUserId()).isEqualTo("u-alice");
        assertThat(saved.getTenantId()).isEqualTo("tenant-a");
        assertThat(saved.getUpdatedAt()).isNotBlank();
        verify(mapper).upsert(state);
    }

    @Test
    void saveRejectsAnotherUsersState() {
        HelpSessionState state = new HelpSessionState();
        state.setThreadId("t-2");
        state.setUserId("u-bob");
        state.setTenantId("tenant-a");

        assertThatThrownBy(() -> service.save(state, ALICE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another user");
        verify(mapper, never()).upsert(any(HelpSessionState.class));
    }

    @Test
    void loadOwnStateWorks() {
        when(mapper.find("t-1")).thenReturn(ownedBy("t-1", ALICE));

        HelpSessionState loaded = service.load("t-1", ALICE);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getThreadId()).isEqualTo("t-1");
        assertThat(loaded.getUserId()).isEqualTo("u-alice");
        verify(mapper).find("t-1");
    }

    @Test
    void loadOtherUsersStateReturnsNull() {
        when(mapper.find("t-1")).thenReturn(ownedBy("t-1", BOB));

        assertThat(service.load("t-1", ALICE)).isNull();
        assertThat(service.load("t-1", CAROL)).isNull();
    }

    @Test
    void loadMissingStateReturnsNull() {
        when(mapper.find("t-404")).thenReturn(null);

        assertThat(service.load("t-404", ALICE)).isNull();
        assertThat(service.load("  ", ALICE)).isNull();
    }

    @Test
    void legacyNullOwnerCanBeClaimed() {
        HelpSessionState legacy = new HelpSessionState();
        legacy.setThreadId("t-legacy");
        legacy.setGoal("历史会话目标");
        when(mapper.find("t-legacy")).thenReturn(legacy);

        HelpSessionState loaded = service.load("t-legacy", ALICE);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).isNull();

        service.save(loaded, ALICE);

        assertThat(loaded.getUserId()).isEqualTo("u-alice");
        assertThat(loaded.getTenantId()).isEqualTo("tenant-a");
        assertThat(loaded.getGoal()).isEqualTo("历史会话目标");
        verify(mapper).upsert(legacy);
    }

    @Test
    void mergeAfterRouteUpdatesRunIdDomainActionPending() {
        HelpSessionState state = new HelpSessionState();
        state.setThreadId("t-1");
        state.setGoal("这个 run 为什么失败");
        state.setLastDomain("PRODUCT_HELP");
        state.setLastRouteAction("PLAYBOOK");
        state.setLastRunId("run-old");

        HelpSessionState merged = service.mergeAfterRoute(
                state, "RUN_DIAGNOSIS", "HELP_GRAPH", "run-123", true);

        assertThat(merged).isSameAs(state);
        assertThat(merged.getLastDomain()).isEqualTo("RUN_DIAGNOSIS");
        assertThat(merged.getLastRouteAction()).isEqualTo("HELP_GRAPH");
        assertThat(merged.getLastRunId()).isEqualTo("run-123");
        assertThat(merged.isPendingClarification()).isTrue();
        // goal is preserved by the merge
        assertThat(merged.getGoal()).isEqualTo("这个 run 为什么失败");
    }

    @Test
    void mergeAfterRouteKeepsPreviousValuesOnBlankInput() {
        HelpSessionState state = new HelpSessionState();
        state.setThreadId("t-1");
        state.setLastRunId("run-keep");
        state.setLastDomain("RESEARCH");

        service.mergeAfterRoute(state, "  ", null, "", false);

        assertThat(state.getLastRunId()).isEqualTo("run-keep");
        assertThat(state.getLastDomain()).isEqualTo("RESEARCH");
        assertThat(state.getLastRouteAction()).isNull();
        assertThat(state.isPendingClarification()).isFalse();
    }

    @Test
    void mergeCheckpointStoresJson() {
        HelpSessionState state = new HelpSessionState();
        state.setThreadId("t-1");
        String checkpoint = "{\"step\":\"awaiting_run_id\",\"turns\":2}";

        HelpSessionState merged = service.mergeCheckpoint(state, checkpoint);

        assertThat(merged).isSameAs(state);
        assertThat(merged.getCheckpointJson()).isEqualTo(checkpoint);
        // null must not wipe an existing checkpoint
        service.mergeCheckpoint(state, null);
        assertThat(state.getCheckpointJson()).isEqualTo(checkpoint);
    }

    @Test
    void clearOwnStateDeletes() {
        when(mapper.find("t-1")).thenReturn(ownedBy("t-1", ALICE));

        service.clear("t-1", ALICE);

        verify(mapper).delete("t-1");
    }

    @Test
    void clearRejectsOtherUser() {
        when(mapper.find("t-1")).thenReturn(ownedBy("t-1", BOB));

        assertThatThrownBy(() -> service.clear("t-1", ALICE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another user");
        verify(mapper, never()).delete(anyString());
    }

    @Test
    void clearMissingStateIsNoOp() {
        when(mapper.find("t-404")).thenReturn(null);

        service.clear("t-404", ALICE);

        verify(mapper).find("t-404");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void nowReturnsStableSortableTimestamp() {
        String now = HelpSessionStateService.now();

        assertThat(now).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    private static HelpSessionState ownedBy(String threadId, AuthenticatedPrincipal principal) {
        HelpSessionState state = new HelpSessionState();
        state.setThreadId(threadId);
        state.setUserId(principal.getUserId());
        state.setTenantId(principal.getTenantId());
        state.setUpdatedAt("2024-01-01 00:00:00");
        return state;
    }
}
