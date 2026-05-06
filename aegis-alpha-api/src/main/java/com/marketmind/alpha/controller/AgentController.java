package com.marketmind.alpha.controller;

import com.marketmind.alpha.domain.AgentTemplate;
import com.marketmind.alpha.service.AgentService;
import com.marketmind.alpha.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/_backend")
public class AgentController {
    private final AuthService authService;
    private final AgentService agentService;

    public AgentController(AuthService authService, AgentService agentService) {
        this.authService = authService;
        this.agentService = agentService;
    }

    @GetMapping("/agents")
    public ResponseEntity<List<AgentTemplate>> agents(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(agentService.findAll());
    }

    @PostMapping("/agents")
    public ResponseEntity<AgentTemplate> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(agentService.create(String.valueOf(me.get("username")), body));
    }

    @PutMapping("/agents/{agentId}")
    public ResponseEntity<AgentTemplate> update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String agentId,
                                                @RequestBody Map<String, Object> body) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(agentService.update(String.valueOf(me.get("username")), agentId, body));
    }

    @PostMapping("/agents/{agentId}/copy")
    public ResponseEntity<AgentTemplate> copy(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String agentId) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(agentService.copy(String.valueOf(me.get("username")), agentId));
    }

    @PostMapping("/agents/{agentId}/run")
    public ResponseEntity<Map<String, Object>> run(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable String agentId,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(agentService.run(String.valueOf(me.get("username")), agentId, body));
    }

    @DeleteMapping("/agents/{agentId}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String agentId) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        agentService.delete(String.valueOf(me.get("username")), agentId);
        return ResponseEntity.noContent().build();
    }
}
