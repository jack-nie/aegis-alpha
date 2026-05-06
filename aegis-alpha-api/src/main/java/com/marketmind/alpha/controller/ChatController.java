package com.marketmind.alpha.controller;

import com.marketmind.alpha.service.AuthService;
import com.marketmind.alpha.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(chatService.threads());
    }

    @PostMapping("/chat/messages")
    public ResponseEntity<Map<String, Object>> reply(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestBody Map<String, Object> body) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(chatService.reply(body));
    }
}
