package com.aegis.alpha.domain;

/**
 * Per-thread Help session state: goal + last routing result + checkpoint metadata.
 *
 * <p>Backed by {@code help_thread_state}. Owner columns ({@code user_id}, {@code tenant_id}) can be
 * empty on legacy rows written before ownership was tracked; such a row is readable and can be
 * claimed on the next save (see {@code HelpSessionStateService}).</p>
 */
public class HelpSessionState {
    private String threadId;
    private String userId;
    private String tenantId;
    private String goal;
    private String lastRunId;
    private String lastDomain;
    private String lastRouteAction;
    private boolean pendingClarification;
    private String checkpointJson;
    private String updatedAt;

    public HelpSessionState() {
    }

    /** Creates an empty state for a thread (owner is filled in on save). */
    public static HelpSessionState forThread(String threadId) {
        HelpSessionState state = new HelpSessionState();
        state.setThreadId(threadId);
        return state;
    }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public String getLastRunId() { return lastRunId; }
    public void setLastRunId(String lastRunId) { this.lastRunId = lastRunId; }

    public String getLastDomain() { return lastDomain; }
    public void setLastDomain(String lastDomain) { this.lastDomain = lastDomain; }

    public String getLastRouteAction() { return lastRouteAction; }
    public void setLastRouteAction(String lastRouteAction) { this.lastRouteAction = lastRouteAction; }

    public boolean isPendingClarification() { return pendingClarification; }
    public void setPendingClarification(boolean pendingClarification) { this.pendingClarification = pendingClarification; }

    public String getCheckpointJson() { return checkpointJson; }
    public void setCheckpointJson(String checkpointJson) { this.checkpointJson = checkpointJson; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
