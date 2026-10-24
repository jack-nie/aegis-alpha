package com.aegis.alpha.controller;

import com.aegis.alpha.service.WorkflowValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Decision D3: validation errors -> 400; other IllegalArgumentExceptions keep the 401 contract. */
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void workflowValidationIsBadRequest() {
        ResponseEntity<Map<String, Object>> response =
                handler.workflowValidation(new WorkflowValidationException("Workflow validation failed: x"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Workflow validation failed: x")
                .containsEntry("code", "WORKFLOW_VALIDATION");
    }

    @Test
    void otherIllegalArgumentKeepsHistoricalStatus() {
        ResponseEntity<Map<String, Object>> response = handler.badRequest(new IllegalArgumentException("bad"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
