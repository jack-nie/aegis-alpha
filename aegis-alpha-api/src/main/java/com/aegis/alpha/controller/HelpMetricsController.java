package com.aegis.alpha.controller;

import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.help.tool.HelpToolGateway;
import com.aegis.alpha.service.help.trace.HelpTraceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only observability endpoint for the Help layer.
 *
 * <p>Exposes in-memory request traces (route, provider, cache, escalation, latency
 * histogram) and tool-gateway counters/audit size. Deliberately authenticated and
 * aggregated: no prompts, answers, run ids or chunk payloads are returned here.</p>
 */
@RestController
@RequestMapping("/_backend")
public class HelpMetricsController {
    private final AuthService authService;
    private final HelpTraceService helpTraceService;
    private final HelpToolGateway helpToolGateway;

    public HelpMetricsController(AuthService authService,
                                 HelpTraceService helpTraceService,
                                 HelpToolGateway helpToolGateway) {
        this.authService = authService;
        this.helpTraceService = helpTraceService;
        this.helpToolGateway = helpToolGateway;
    }

    @GetMapping("/help/metrics")
    public ResponseEntity<Map<String, Object>> metrics(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("help", helpTraceService.snapshot());
        response.put("tools", helpToolGateway.metricsSnapshot());
        return ResponseEntity.ok(response);
    }
}
