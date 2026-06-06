package com.aegis.alpha.controller;

import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.MarketDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/_backend/market-data")
public class MarketDataController {
    private final AuthService authService;
    private final MarketDataService marketDataService;

    public MarketDataController(AuthService authService, MarketDataService marketDataService) {
        this.authService = authService;
        this.marketDataService = marketDataService;
    }

    @GetMapping("/quote")
    public ResponseEntity<Map<String, Object>> quote(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestParam String symbol) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(marketDataService.quote(symbol));
    }

    @GetMapping("/financials")
    public ResponseEntity<Map<String, Object>> financials(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @RequestParam String symbol) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(marketDataService.financials(symbol));
    }

    @GetMapping("/news")
    public ResponseEntity<Map<String, Object>> news(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @RequestParam String symbol) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(marketDataService.news(symbol));
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                        @RequestParam String symbol) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(marketDataService.overview(symbol));
    }
}
