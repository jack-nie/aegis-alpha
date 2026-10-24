package com.aegis.alpha.controller;

import com.aegis.alpha.security.AuthenticatedPrincipal;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/_backend")
public class ChatController {
    private final AuthService authService;
    private final ChatService chatService;

    public ChatController(AuthService authService, ChatService chatService) {
        this.authService = authService;
        this.chatService = chatService;
    }

    @GetMapping("/api/chat/threads")
    public ResponseEntity<Object> threads(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(chatService.threads(principal));
    }

    /** P4-11: recent messages of one of the caller's threads (chronological). */
    @GetMapping("/api/chat/threads/{threadId}/messages")
    public ResponseEntity<Object> threadMessages(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable("threadId") String threadId,
                                                 @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Object messages = chatService.threadMessages(threadId, principal, limit);
            if (messages == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.ok(messages);
        } catch (SecurityException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    /** D2: admin assigns an unowned legacy thread to {userId, tenantId}. */
    @PostMapping("/api/chat/threads/{threadId}/owner")
    public ResponseEntity<Map<String, Object>> assignLegacyThread(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("threadId") String threadId,
            @RequestBody(required = false) Map<String, Object> body) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Object userId = body == null ? null : body.get("userId");
        Object tenantId = body == null ? null : body.get("tenantId");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("threadId", threadId);
        try {
            ChatService.AssignOutcome outcome = chatService.assignLegacyThread(threadId,
                    userId == null ? null : String.valueOf(userId),
                    tenantId == null ? null : String.valueOf(tenantId), principal);
            out.put("outcome", outcome.name());
            if (outcome == ChatService.AssignOutcome.NOT_FOUND) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(out);
            }
            if (outcome == ChatService.AssignOutcome.ALREADY_OWNED) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
            }
            return ResponseEntity.ok(out);
        } catch (SecurityException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException ex) {
            out.put("error", ex.getMessage());
            return ResponseEntity.badRequest().body(out);
        }
    }

    @PostMapping("/chat/messages")
    public ResponseEntity<Map<String, Object>> reply(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestBody Map<String, Object> body) {
        AuthenticatedPrincipal principal = authService.principal(authorization);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(chatService.reply(body, principal));
        } catch (IllegalArgumentException ex) {
            /* Thread ownership violations are client errors, not server failures. */
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("ok", false);
            error.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
        }
    }
}
