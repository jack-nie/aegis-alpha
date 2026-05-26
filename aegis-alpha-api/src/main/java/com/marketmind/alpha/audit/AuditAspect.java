package com.marketmind.alpha.audit;

import com.marketmind.alpha.security.AuthenticatedPrincipal;
import com.marketmind.alpha.service.AuthService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

@Aspect
@Component
public class AuditAspect {

    private final AuditService auditService;
    private final AuthService authService;

    public AuditAspect(AuditService auditService, AuthService authService) {
        this.auditService = auditService;
        this.authService = authService;
    }

    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String uri = request.getRequestURI();
            if (uri != null && uri.contains("/_backend/auth/login")) {
                return result;
            }

            String bearer = request.getHeader("Authorization");
            AuthenticatedPrincipal principal = bearer == null ? null : authService.principal(bearer);

            String action = String.format("api.%s %s", request.getMethod().toLowerCase(), uri);
            auditService.recordPrincipalAction(principal, action, "endpoint", uri);
        } catch (Exception ignored) {
            // audit should not break business flow
        }

        return result;
    }
}
