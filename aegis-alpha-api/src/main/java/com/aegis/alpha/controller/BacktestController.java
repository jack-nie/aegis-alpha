package com.aegis.alpha.controller;

import com.aegis.alpha.domain.BacktestRun;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.BacktestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/_backend/backtest")
public class BacktestController {
    private final AuthService authService;
    private final BacktestService backtestService;

    public BacktestController(AuthService authService, BacktestService backtestService) {
        this.authService = authService;
        this.backtestService = backtestService;
    }

    @GetMapping("/history")
    public ResponseEntity<List<BacktestRun>> history(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(backtestService.findAll());
    }

    @PostMapping("/history")
    public ResponseEntity<BacktestRun> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody Map<String, Object> body) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(backtestService.create(string(body.get("runName")), string(body.get("strategy"))));
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
