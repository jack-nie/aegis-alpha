package com.aegis.alpha.service;

/**
 * The LangGraph orchestrator was enabled but the call failed (transport error, timeout or no body).
 * Distinct from the explicit local mode ({@code aegis.langchain.enabled=false}), whose deterministic
 * output is allowed; a failed call must surface as a node/run failure instead of fabricated output.
 */
public class OrchestratorCallException extends RuntimeException {
    public OrchestratorCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
