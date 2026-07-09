package com.aegis.alpha.controller;

import com.aegis.alpha.domain.Portfolio;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.PortfolioService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/_backend/portfolio")
public class PortfolioController {
    private final AuthService authService;
    private final PortfolioService portfolioService;
    private final String nodeExecutionToken;

    public PortfolioController(AuthService authService,
                               PortfolioService portfolioService,
                               @Value("${aegis.dify.node-execution-token:local-workflow-node-token}") String nodeExecutionToken) {
        this.authService = authService;
        this.portfolioService = portfolioService;
        this.nodeExecutionToken = nodeExecutionToken;
    }

    /** User token or internal service token (research agent read path). */
    private boolean isReadAuthorized(String authorization) {
        if (authorization == null) {
            return false;
        }
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        if (nodeExecutionToken != null && nodeExecutionToken.equals(token)) {
            return true;
        }
        return authService.me(authorization) != null;
    }

    @GetMapping("/portfolios")
    public ResponseEntity<List<Portfolio>> portfolios(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(portfolioService.findAll());
    }

    @PostMapping("/portfolios")
    public ResponseEntity<Portfolio> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody Map<String, String> body) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(portfolioService.create(body.get("name")));
    }

    @GetMapping("/{portfolioId}/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @PathVariable String portfolioId) {
        if (!isReadAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(portfolioService.summaryContract(portfolioId));
    }

    @GetMapping("/{portfolioId}/positions")
    public ResponseEntity<Map<String, Object>> positions(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @PathVariable String portfolioId) {
        if (!isReadAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(portfolioService.positionsContract(portfolioId));
    }

    @GetMapping("/{portfolioId}/trades")
    public ResponseEntity<Map<String, Object>> trades(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @PathVariable String portfolioId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(portfolioService.tradesContract(portfolioId));
    }
}
