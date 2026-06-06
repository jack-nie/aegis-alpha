package com.aegis.alpha.controller;

import com.aegis.alpha.domain.LlmCall;
import com.aegis.alpha.domain.ModelConfig;
import com.aegis.alpha.domain.Recommendation;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.ModelGovernanceService;
import com.aegis.alpha.service.RecommendationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/_backend")
public class GovernanceController {
    private final AuthService authService;
    private final ModelGovernanceService modelGovernanceService;
    private final RecommendationService recommendationService;

    public GovernanceController(AuthService authService,
                                ModelGovernanceService modelGovernanceService,
                                RecommendationService recommendationService) {
        this.authService = authService;
        this.modelGovernanceService = modelGovernanceService;
        this.recommendationService = recommendationService;
    }

    @GetMapping("/governance/models")
    public ResponseEntity<List<ModelConfig>> models(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(modelGovernanceService.models());
    }

    @GetMapping("/governance/llm-calls")
    public ResponseEntity<List<LlmCall>> llmCalls(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @RequestParam String workflowRunId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(modelGovernanceService.llmCalls(workflowRunId));
    }

    @GetMapping("/recommendations")
    public ResponseEntity<List<Recommendation>> recommendations(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(recommendationService.recommendations());
    }

    @GetMapping("/recommendations/{workflowRunId}")
    public ResponseEntity<Map<String, Object>> recommendationDetail(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                                    @PathVariable String workflowRunId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Object> detail = recommendationService.detail(workflowRunId);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    @PostMapping("/recommendations/{workflowRunId}/approve")
    public ResponseEntity<Recommendation> approveRecommendation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                               @PathVariable String workflowRunId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Recommendation recommendation = recommendationService.approve(workflowRunId);
        return recommendation == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(recommendation);
    }

    @PostMapping("/recommendations/{workflowRunId}/reject")
    public ResponseEntity<Recommendation> rejectRecommendation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                              @PathVariable String workflowRunId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Recommendation recommendation = recommendationService.reject(workflowRunId);
        return recommendation == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(recommendation);
    }
}
