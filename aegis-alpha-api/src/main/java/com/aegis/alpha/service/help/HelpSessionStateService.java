package com.aegis.alpha.service.help;

import com.aegis.alpha.domain.HelpSessionState;
import com.aegis.alpha.mapper.HelpSessionMapper;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Owns per-thread Help session state (goal, last routing result, checkpoint metadata).
 *
 * <p>Every read and write is owner-scoped:</p>
 * <ul>
 *   <li>{@link #load(String, AuthenticatedPrincipal)} hides state owned by somebody else (returns {@code null}).</li>
 *   <li>{@link #save(HelpSessionState, AuthenticatedPrincipal)} claims an unowned (legacy) row and rejects a
 *       write against another user's row with {@link IllegalArgumentException}.</li>
 *   <li>{@link #clear(String, AuthenticatedPrincipal)} deletes only the caller's own row.</li>
 * </ul>
 *
 * <p>Nothing here touches ChatService / HelpService; the caller decides when to persist.</p>
 */
@Service
public class HelpSessionStateService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HelpSessionMapper mapper;

    public HelpSessionStateService(HelpSessionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Loads the persisted state for a thread.
     *
     * @return the state, or {@code null} when nothing is stored, when {@code threadId} is blank, or when the
     *         stored owner does not match {@code principal}. A legacy row with no owner is returned as-is so the
     *         next {@link #save(HelpSessionState, AuthenticatedPrincipal)} can claim it.
     */
    public HelpSessionState load(String threadId, AuthenticatedPrincipal principal) {
        String id = trimmedOrNull(threadId);
        if (id == null) {
            return null;
        }
        HelpSessionState state = mapper.find(id);
        if (state == null) {
            return null;
        }
        if (!isVisibleTo(state, principal)) {
            return null;
        }
        return state;
    }

    /**
     * Upserts the state, stamping {@code updated_at}. A missing owner is taken from {@code principal}; a state
     * already owned by a different user/tenant is rejected.
     *
     * @return the same state instance, now carrying owner + timestamp
     * @throws IllegalArgumentException when the state/thread id is missing or the owner does not match
     */
    public HelpSessionState save(HelpSessionState state, AuthenticatedPrincipal principal) {
        if (state == null) {
            throw new IllegalArgumentException("help session state must not be null");
        }
        String id = trimmedOrNull(state.getThreadId());
        if (id == null) {
            throw new IllegalArgumentException("help session state requires a threadId");
        }
        if (principal == null) {
            throw new IllegalArgumentException("help session state requires an authenticated principal");
        }
        state.setThreadId(id);
        bindOwner(state, principal);
        state.setUpdatedAt(now());
        mapper.upsert(state);
        return state;
    }

    /**
     * Records the outcome of a routing decision on the state.
     *
     * <p>Blank inputs leave the previous value untouched, so a follow-up question that carries no run id still
     * remembers the last known run/domain. The goal is never modified here: a non-empty goal is kept as-is.</p>
     *
     * @return the same state instance ({@code null} when {@code state} is {@code null})
     */
    public HelpSessionState mergeAfterRoute(HelpSessionState state,
                                            String domain,
                                            String routeAction,
                                            String runId,
                                            boolean pendingClarification) {
        if (state == null) {
            return null;
        }
        String nextDomain = trimmedOrNull(domain);
        if (nextDomain != null) {
            state.setLastDomain(nextDomain);
        }
        String nextAction = trimmedOrNull(routeAction);
        if (nextAction != null) {
            state.setLastRouteAction(nextAction);
        }
        String nextRunId = trimmedOrNull(runId);
        if (nextRunId != null) {
            state.setLastRunId(nextRunId);
        }
        state.setPendingClarification(pendingClarification);
        return state;
    }

    /**
     * Stores the checkpoint blob for the state.
     *
     * <p>{@code null} leaves the stored checkpoint untouched; a blank value clears it (explicit reset); any other
     * value is stored trimmed.</p>
     *
     * @return the same state instance ({@code null} when {@code state} is {@code null})
     */
    public HelpSessionState mergeCheckpoint(HelpSessionState state, String checkpointJson) {
        if (state == null || checkpointJson == null) {
            return state;
        }
        state.setCheckpointJson(trimmedOrNull(checkpointJson));
        return state;
    }

    /**
     * Deletes the thread state. Idempotent: clearing an unknown thread is a no-op.
     *
     * @throws IllegalArgumentException when the stored state belongs to a different user/tenant
     */
    public void clear(String threadId, AuthenticatedPrincipal principal) {
        String id = trimmedOrNull(threadId);
        if (id == null) {
            return;
        }
        HelpSessionState existing = mapper.find(id);
        if (existing == null) {
            return;
        }
        if (!isVisibleTo(existing, principal)) {
            throw new IllegalArgumentException("help session state for thread " + id + " belongs to another user");
        }
        mapper.delete(id);
    }

    /** Timestamp helper matching the VARCHAR(32) {@code updated_at} column. */
    public static String now() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }

    /**
     * Fills in a missing owner from the principal and rejects a foreign owner.
     * Legacy rows (no {@code user_id} / {@code tenant_id}) are claimed by whoever saves next.
     */
    private static void bindOwner(HelpSessionState state, AuthenticatedPrincipal principal) {
        String ownerId = trimmedOrNull(state.getUserId());
        String principalId = trimmedOrNull(principal.getUserId());
        if (ownerId == null) {
            state.setUserId(principalId);
        } else if (principalId == null || !ownerId.equals(principalId)) {
            throw new IllegalArgumentException("help session state for thread "
                    + state.getThreadId() + " belongs to another user");
        }

        String ownerTenant = trimmedOrNull(state.getTenantId());
        String principalTenant = trimmedOrNull(principal.getTenantId());
        if (ownerTenant == null) {
            state.setTenantId(principalTenant);
        } else if (principalTenant == null || !ownerTenant.equals(principalTenant)) {
            throw new IllegalArgumentException("help session state for thread "
                    + state.getThreadId() + " belongs to another tenant");
        }
    }

    /**
     * An unowned row is visible (legacy, claimable); an owned row is visible only when every populated owner
     * field (user, tenant) matches the principal. A missing principal can never see an owned row.
     */
    private static boolean isVisibleTo(HelpSessionState state, AuthenticatedPrincipal principal) {
        String ownerId = trimmedOrNull(state.getUserId());
        String ownerTenant = trimmedOrNull(state.getTenantId());
        if (ownerId == null && ownerTenant == null) {
            return true;
        }
        if (principal == null) {
            return false;
        }
        String principalId = trimmedOrNull(principal.getUserId());
        String principalTenant = trimmedOrNull(principal.getTenantId());
        if (ownerId != null && !ownerId.equals(principalId)) {
            return false;
        }
        if (ownerTenant != null && !ownerTenant.equals(principalTenant)) {
            return false;
        }
        return true;
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
