package com.aegis.alpha.controller;

import com.aegis.alpha.service.WorkflowValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * Decision D3: workflow validation errors are client input errors -> 400 (was 401 through the
     * generic handler below). Body keeps {@code message}; {@code code} is new.
     */
    @ExceptionHandler(WorkflowValidationException.class)
    public ResponseEntity<Map<String, Object>> workflowValidation(WorkflowValidationException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("message", ex.getMessage());
        body.put("code", "WORKFLOW_VALIDATION");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Other IllegalArgumentExceptions keep the historical 401 contract (unchanged, see ApiContractTest). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
}
