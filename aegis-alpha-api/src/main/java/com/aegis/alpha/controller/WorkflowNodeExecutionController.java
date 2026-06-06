package com.aegis.alpha.controller;

import com.aegis.alpha.service.WorkflowNodeExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/_backend/internal/workflow-nodes")
public class WorkflowNodeExecutionController {
    private final WorkflowNodeExecutionService executionService;

    public WorkflowNodeExecutionController(WorkflowNodeExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(@RequestHeader(value = "X-Aegis-Workflow-Token", required = false) String token,
                                                       @RequestBody Map<String, Object> request) {
        if (!executionService.authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(executionService.execute(request));
    }
}
