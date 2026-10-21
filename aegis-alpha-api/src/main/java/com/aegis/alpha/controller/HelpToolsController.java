package com.aegis.alpha.controller;

import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.help.tool.HelpToolCatalog;
import com.aegis.alpha.service.help.tool.HelpToolDefinition;
import com.aegis.alpha.service.help.tool.HelpToolGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only Help tool listing: catalog, recent audit tail and gateway counters.
 */
@RestController
@RequestMapping("/_backend/help")
public class HelpToolsController {
    private static final int AUDIT_LIMIT = 20;

    private final AuthService authService;
    private final HelpToolCatalog catalog;
    private final HelpToolGateway gateway;

    public HelpToolsController(AuthService authService, HelpToolCatalog catalog, HelpToolGateway gateway) {
        this.authService = authService;
        this.catalog = catalog;
        this.gateway = gateway;
    }

    @GetMapping("/tools")
    public ResponseEntity<Map<String, Object>> tools(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authenticated(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        for (HelpToolDefinition definition : catalog.all()) {
            tools.add(definition.toMap());
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("tools", tools);
        body.put("audit", gateway.audit(AUDIT_LIMIT));
        body.put("metrics", gateway.metricsSnapshot());
        return ResponseEntity.ok(body);
    }

    private boolean authenticated(String authorization) {
        if (authorization == null || authorization.trim().isEmpty()) {
            return false;
        }
        return authService.me(authorization) != null;
    }
}
