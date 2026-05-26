package com.marketmind.alpha.audit;

import com.marketmind.alpha.mapper.AuditEventMapper;
import com.marketmind.alpha.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditEventMapper mapper;

    public AuditService(AuditEventMapper mapper) {
        this.mapper = mapper;
    }

    public List<AuditEvent> latest(int limit) {
        int bounded = limit <= 0 ? 100 : Math.min(limit, 100);
        return mapper.findLatest(bounded);
    }

    public void record(AuditEvent event) {
        if (event.getEventId() == null || event.getEventId().trim().isEmpty()) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getActorType() == null || event.getActorType().trim().isEmpty()) {
            event.setActorType("USER");
        }
        if (event.getCreatedAt() == null || event.getCreatedAt().trim().isEmpty()) {
            event.setCreatedAt(LocalDateTime.now().toString());
        }
        enrichFromRequest(event);
        mapper.insert(event);
    }

    public void recordPrincipalAction(AuthenticatedPrincipal principal, String action, String resourceType, String resourceId) {
        AuditEvent event = new AuditEvent();
        if (principal != null) {
            event.setTenantId(principal.getTenantId());
            event.setUserId(principal.getUserId());
        }
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        record(event);
    }

    public void recordLoginFailure(String username, String tenantId, String userId) {
        AuditEvent event = new AuditEvent();
        event.setTenantId(tenantId);
        event.setUserId(userId);
        event.setAction("auth.login.failure");
        event.setResourceType("user");
        event.setResourceId(username);
        record(event);
    }

    /**
     * Enrich audit event with request context: IP address, user agent,
     * request ID, and trace ID.  Silently skipped when called outside of
     * an HTTP request scope (e.g. scheduled jobs).
     */
    private void enrichFromRequest(AuditEvent event) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }

        if (event.getIpAddress() == null || event.getIpAddress().trim().isEmpty()) {
            event.setIpAddress(resolveClientIp(request));
        }
        if (event.getUserAgent() == null || event.getUserAgent().trim().isEmpty()) {
            String ua = request.getHeader("User-Agent");
            if (ua != null && ua.length() > 512) {
                ua = ua.substring(0, 512);
            }
            event.setUserAgent(ua);
        }
        if (event.getRequestId() == null || event.getRequestId().trim().isEmpty()) {
            String rid = request.getHeader("X-Request-ID");
            if (rid == null || rid.trim().isEmpty()) {
                rid = request.getHeader("X-Request-Id");
            }
            if (rid == null || rid.trim().isEmpty()) {
                rid = UUID.randomUUID().toString();
            }
            event.setRequestId(rid);
        }
        if (event.getTraceId() == null || event.getTraceId().trim().isEmpty()) {
            String tid = request.getHeader("X-Trace-ID");
            if (tid == null || tid.trim().isEmpty()) {
                tid = request.getHeader("traceparent");
            }
            if (tid != null && tid.length() > 64) {
                tid = tid.substring(0, 64);
            }
            event.setTraceId(tid);
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            // X-Forwarded-For: client, proxy1, proxy2 — take the first one
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
