package com.aegis.alpha.audit;

import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/_backend/admin")
public class AuditController {
    private final AuthService authService;
    private final AuditService auditService;

    public AuditController(AuthService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
    }

    @GetMapping("/audit-events")
    public ResponseEntity<List<AuditEvent>> auditEvents(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(auditService.latest(100));
    }
}
