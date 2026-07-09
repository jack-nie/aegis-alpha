package com.aegis.alpha.controller;

import com.aegis.alpha.service.AuthService;
import com.aegis.alpha.service.PortfolioService;
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

class PortfolioControllerAuthTest {
    private AuthService authService;
    private TokenService tokenService;
    private PortfolioController controller;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        PortfolioService portfolioService = mock(PortfolioService.class);
        tokenService = new TokenService("test-secret-key-1234567890", new ObjectMapper());
        controller = new PortfolioController(
                authService,
                portfolioService,
                tokenService,
                "local-workflow-node-token");
    }

    @Test
    void nullAuthorizationDenied() {
        assertThat(controller.isReadAuthorized(null)).isFalse();
    }

    @Test
    void nodeExecutionTokenAllowed() {
        assertThat(controller.isReadAuthorized("Bearer local-workflow-node-token")).isTrue();
        assertThat(controller.isReadAuthorized("local-workflow-node-token")).isTrue();
    }

    @Test
    void userTokenAllowedViaAuthService() {
        Map<String, Object> me = new HashMap<String, Object>();
        me.put("user_id", "u-1");
        when(authService.me("Bearer user-token")).thenReturn(me);

        assertThat(controller.isReadAuthorized("Bearer user-token")).isTrue();
    }

    @Test
    void delegationTokenWithPortfolioReadAllowed() {
        String token = tokenService.issueServiceDelegation(
                "run-1",
                "uid-1",
                "tenant-1",
                Collections.singletonList("portfolio:read"),
                60_000L);

        assertThat(controller.isReadAuthorized("Bearer " + token)).isTrue();
        assertThat(controller.isReadAuthorized(token)).isTrue();
    }

    @Test
    void expiredDelegationDenied() {
        String token = tokenService.issueServiceDelegation(
                "run-1",
                "uid-1",
                "tenant-1",
                Collections.singletonList("portfolio:read"),
                -1_000L);

        assertThat(controller.isReadAuthorized("Bearer " + token)).isFalse();
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

        assertThat(controller.isReadAuthorized("Bearer " + token)).isFalse();
    }

    @Test
    void badSignatureDenied() {
        String token = tokenService.issueServiceDelegation(
                "run-1",
                "uid-1",
                "tenant-1",
                Collections.singletonList("portfolio:read"),
                60_000L);
        when(authService.me(anyString())).thenReturn(null);

        assertThat(controller.isReadAuthorized("Bearer " + token + "tamper")).isFalse();
    }
}
