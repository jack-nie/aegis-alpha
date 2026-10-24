package com.aegis.alpha.service;

/**
 * Decision D3: a workflow layout / node configuration failed validation. Mapped to HTTP 400 by
 * {@code ApiExceptionHandler} (it used to fall into the generic IllegalArgumentException -> 401
 * mapping). Extends IllegalArgumentException so existing internal catches keep working.
 */
public class WorkflowValidationException extends IllegalArgumentException {
    public WorkflowValidationException(String message) {
        super(message);
    }
}
