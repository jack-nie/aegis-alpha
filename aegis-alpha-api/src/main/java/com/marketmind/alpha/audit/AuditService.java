package com.marketmind.alpha.audit;

import com.marketmind.alpha.mapper.AuditEventMapper;
import com.marketmind.alpha.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

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
}
