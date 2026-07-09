package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceDelegationTest {
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService("test-secret-key-1234567890", new ObjectMapper());
    }

    @Test
    void issueServiceDelegationPayloadFields() {
        String token = tokenService.issueServiceDelegation(
                "run-1",
                "uid-1",
                "tenant-1",
                Arrays.asList("portfolio:read"),
                60_000L);

        Map<String, Object> payload = tokenService.verify(token);

        assertThat(payload).isNotNull();
        assertThat(payload.get("typ")).isEqualTo("delegation");
        assertThat(payload.get("runId")).isEqualTo("run-1");
        assertThat(payload.get("uid")).isEqualTo("uid-1");
        assertThat(payload.get("tenantId")).isEqualTo("tenant-1");
        assertThat(payload.get("scopes")).isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        java.util.List<String> scopes = (java.util.List<String>) payload.get("scopes");
        assertThat(scopes).containsExactly("portfolio:read");
        assertThat(((Number) payload.get("exp")).longValue()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void expiredDelegationFailsVerify() {
        String token = tokenService.issueServiceDelegation(
                "run-expired",
                "uid-1",
                "tenant-1",
                Collections.singletonList("portfolio:read"),
                -5_000L);

        assertThat(tokenService.verify(token)).isNull();
        assertThat(tokenService.hasDelegationScope(token, "portfolio:read")).isFalse();
    }

    @Test
    void hasDelegationScopeRequiresTypAndScope() {
        String token = tokenService.issueServiceDelegation(
                "run-2",
                "uid-2",
                "tenant-2",
                Collections.singletonList("portfolio:read"),
                60_000L);

        assertThat(tokenService.hasDelegationScope(token, "portfolio:read")).isTrue();
        assertThat(tokenService.hasDelegationScope(token, "portfolio:write")).isFalse();
        assertThat(tokenService.hasDelegationScope(null, "portfolio:read")).isFalse();
        assertThat(tokenService.hasDelegationScope(token, null)).isFalse();
    }

    @Test
    void userTokenIsNotDelegation() {
        String userToken = tokenService.issue("alice", "uid-1", "tenant-1");

        Map<String, Object> payload = tokenService.verify(userToken);
        assertThat(payload).isNotNull();
        assertThat(payload.get("typ")).isNull();
        assertThat(tokenService.hasDelegationScope(userToken, "portfolio:read")).isFalse();
    }

    @Test
    void badSignatureFailsClosed() {
        String token = tokenService.issueServiceDelegation(
                "run-3",
                "uid-3",
                "tenant-3",
                Collections.singletonList("portfolio:read"),
                60_000L);
        String tampered = token + "x";

        assertThat(tokenService.verify(tampered)).isNull();
        assertThat(tokenService.hasDelegationScope(tampered, "portfolio:read")).isFalse();
    }

    @Test
    void emptyScopesDenied() {
        String token = tokenService.issueServiceDelegation(
                "run-4",
                "uid-4",
                "tenant-4",
                Collections.<String>emptyList(),
                60_000L);

        assertThat(tokenService.verify(token)).isNotNull();
        assertThat(tokenService.hasDelegationScope(token, "portfolio:read")).isFalse();
    }
}
