package com.aegis.alpha.controller;

import com.aegis.alpha.domain.PortfolioTrade;
import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.PortfolioTradeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/_backend/portfolio/trades")
public class PortfolioTradeController {
    private final AuthService authService;
    private final PortfolioTradeService tradeService;

    public PortfolioTradeController(AuthService authService, PortfolioTradeService tradeService) {
        this.authService = authService;
        this.tradeService = tradeService;
    }

    @GetMapping
    public ResponseEntity<List<PortfolioTrade>> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestParam(value = "portfolioId", required = false) String portfolioId,
                                                     @RequestParam(value = "symbol", required = false) String symbol) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(tradeService.findAll(portfolioId, symbol));
    }

    @PostMapping
    public ResponseEntity<PortfolioTrade> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @RequestBody PortfolioTrade trade) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(tradeService.create(trade));
    }

    @PostMapping("/import")
    public ResponseEntity<List<PortfolioTrade>> importRows(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @RequestBody ImportTradesRequest request) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(tradeService.importRows(request.getRows(), request.getImportBatchId()));
    }

    @DeleteMapping("/{tradeId}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String tradeId) {
        if (authService.me(authorization) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!tradeService.delete(tradeId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    public static class ImportTradesRequest {
        private String importBatchId;
        private List<PortfolioTrade> rows;

        public String getImportBatchId() { return importBatchId; }
        public void setImportBatchId(String importBatchId) { this.importBatchId = importBatchId; }
        public List<PortfolioTrade> getRows() { return rows; }
        public void setRows(List<PortfolioTrade> rows) { this.rows = rows; }
    }
}
