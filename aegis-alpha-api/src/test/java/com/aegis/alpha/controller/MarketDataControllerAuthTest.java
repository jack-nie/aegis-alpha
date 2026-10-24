package com.aegis.alpha.controller;

import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.MarketDataService;
import com.aegis.alpha.service.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataControllerAuthTest {
    private AuthService authService;
    private TokenService tokenService;
    private MarketDataController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        tokenService = new TokenService("test-secret-key-1234567890", new ObjectMapper());
        controller = new MarketDataController(
                authService,
                marketDataService,
                tokenService,
                "local-workflow-node-token");
    }

    private int status(String authorization) {
        return controller.quote(authorization, "AAPL").getStatusCodeValue();
    }

    @Test
    void missingAuthorizationDenied() {
        assertThat(status(null)).isEqualTo(401);
    }

    @Test
    void nodeExecutionTokenAllowed() {
        assertThat(status("Bearer local-workflow-node-token")).isEqualTo(200);
    }

    @Test
    void userTokenAllowedViaAuthService() {
        Map<String, Object> me = new HashMap<String, Object>();
        me.put("user_id", "u-1");
        when(authService.me("Bearer user-token")).thenReturn(me);

        assertThat(status("Bearer user-token")).isEqualTo(200);
    }

    @Test
    void delegationTokenWithPortfolioReadAllowed() {
        String token = tokenService.issueServiceDelegation(
                "run-1",
                "uid-1",
                "tenant-1",
                Collections.singletonList(PortfolioController.SCOPE_PORTFOLIO_READ),
                60_000L);

        assertThat(status("Bearer " + token)).isEqualTo(200);
        assertThat(status(token)).isEqualTo(200);
    }

    @Test
    void wrongScopeDelegationDenied() {
        String token = tokenService.issueServiceDelegation(
                "run-1",
                "uid-1",
                "tenant-1",
                Collections.singletonList("other:scope"),
                60_000L);
        when(authService.me(anyString())).thenReturn(null);

        assertThat(status("Bearer " + token)).isEqualTo(401);
    }
}
