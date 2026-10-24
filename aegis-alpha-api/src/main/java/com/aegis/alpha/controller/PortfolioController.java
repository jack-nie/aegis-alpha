package com.aegis.alpha.controller;

import com.aegis.alpha.domain.Portfolio;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.PortfolioService;
import com.aegis.alpha.service.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/_backend/portfolio")
public class PortfolioController {
    static final String SCOPE_PORTFOLIO_READ = "portfolio:read";
    private static final Logger log = LoggerFactory.getLogger(PortfolioController.class);

    private final AuthService authService;
    private final PortfolioService portfolioService;
    private final TokenService tokenService;
    private final String nodeExecutionToken;

    public PortfolioController(AuthService authService,
                               PortfolioService portfolioService,
                               TokenService tokenService,
                               @Value("${aegis.dify.node-execution-token:}") String nodeExecutionToken) {
        this.authService = authService;
        this.portfolioService = portfolioService;
        this.tokenService = tokenService;
        this.nodeExecutionToken = nodeExecutionToken;
    }

    /**
     * Read path auth (positions/summary):
     * 1) user Bearer token (authService.me)
     * 2) service node-execution-token
     * 3) run-scoped delegation token with portfolio:read
     */
    boolean isReadAuthorized(String authorization) {
        return isReadAuthorized(authorization, null);
    }

    /**
     * P2-21: a delegated read is audited with the token subject (uid), run and portfolio.
     * There is no portfolio ownership model yet, so the subject is logged, not enforced (decision: kept; needs an ownership model first).
     */
    boolean isReadAuthorized(String authorization, String portfolioId) {
        if (authorization == null) {
            return false;
        }
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        /* An unset service token must never match an empty bearer. */
        if (nodeExecutionToken != null && !nodeExecutionToken.trim().isEmpty() && nodeExecutionToken.equals(token)) {
            return true;
        }
        if (tokenService.hasDelegationScope(token, SCOPE_PORTFOLIO_READ)) {
            Map<String, Object> claims = tokenService.verify(token);
            log.info("[PortfolioAudit] delegated portfolio:read sub={} tenant={} runId={} portfolioId={}",
                    delegatedSubject(claims), claims == null ? null : claims.get("tenantId"),
                    claims == null ? null : claims.get("runId"), portfolioId);
            return true;
        }
        return authService.me(authorization) != null;
    }

    /** Subject of a delegation token (uid, or sub when present). */
    static String delegatedSubject(Map<String, Object> claims) {
        if (claims == null) {
            return null;
        }
        Object sub = claims.get("sub") != null ? claims.get("sub") : claims.get("uid");
        return sub == null ? null : String.valueOf(sub);
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
        if (!isReadAuthorized(authorization, portfolioId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(portfolioService.summaryContract(portfolioId));
    }

    @GetMapping("/{portfolioId}/positions")
    public ResponseEntity<Map<String, Object>> positions(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @PathVariable String portfolioId) {
        if (!isReadAuthorized(authorization, portfolioId)) {
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
