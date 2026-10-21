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
