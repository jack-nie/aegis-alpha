package com.marketmind.alpha.controller;

import com.marketmind.alpha.service.AuthService;
import com.marketmind.alpha.service.DifyPublishService;
import com.marketmind.alpha.service.DifyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/_backend/dify")
public class DifyController {
    private final AuthService authService;
    private final DifyService difyService;
    private final DifyPublishService difyPublishService;

    public DifyController(AuthService authService, DifyService difyService, DifyPublishService difyPublishService) {
        this.authService = authService;
        this.difyService = difyService;
        this.difyPublishService = difyPublishService;
    }

    @PostMapping("/workflows/{workflowKey}/run")
    public ResponseEntity<Map<String, Object>> runWorkflow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @PathVariable String workflowKey,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Object> inputs = body == null ? new HashMap<String, Object>() : body;
        return ResponseEntity.ok(difyService.runWorkflow(workflowKey, inputs, String.valueOf(me.get("username"))));
    }

    @PostMapping("/agents/{agentId}/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @PathVariable String agentId,
                                                    @RequestBody(required = false) Map<String, String> body) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String message = body == null ? "" : body.get("message");
        return ResponseEntity.ok(difyService.chat(agentId, message, String.valueOf(me.get("username"))));
    }

    @GetMapping("/workflows/{workflowKey}/dsl")
    public ResponseEntity<Map<String, Object>> exportDsl(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @PathVariable String workflowKey) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(difyPublishService.exportDsl(workflowKey));
    }

    @PostMapping("/workflows/{workflowKey}/publish")
    public ResponseEntity<Map<String, Object>> publish(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @PathVariable String workflowKey) {
        Map<String, Object> me = authService.me(authorization);
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(difyPublishService.publish(workflowKey));
    }
}
