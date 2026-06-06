package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService("test-secret-key-1234567890", new ObjectMapper());
    }

    @Test
    void issueAndVerifyRoundTrip() {
        String token = tokenService.issue("alice", "uid-1", "tenant-1");

        Map<String, Object> payload = tokenService.verify(token);

        assertThat(payload).isNotNull();
        assertThat(payload.get("sub")).isEqualTo("alice");
        assertThat(payload.get("uid")).isEqualTo("uid-1");
        assertThat(payload.get("tenantId")).isEqualTo("tenant-1");
    }

    @Test
    void issueWithRoles() {
        String token = tokenService.issue("bob", "uid-2", "tenant-2", Arrays.asList("admin", "analyst"));

        Map<String, Object> payload = tokenService.verify(token);

        assertThat(payload).isNotNull();
        assertThat(payload.get("roles")).isInstanceOf(java.util.List.class);
    }

    @Test
    void verifyReturnsNullForNullToken() {
        assertThat(tokenService.verify(null)).isNull();
    }

    @Test
    void verifyReturnsNullForTokenWithoutDot() {
        assertThat(tokenService.verify("invalidtoken")).isNull();
    }

    @Test
    void verifyReturnsNullForTamperedPayload() {
        String token = tokenService.issue("alice", "uid-1", "tenant-1");
        String[] parts = token.split("\\.", 2);
        String tampered = parts[0] + "X" + "." + parts[1];

        assertThat(tokenService.verify(tampered)).isNull();
    }

    @Test
    void verifyReturnsNullForTamperedSignature() {
        String token = tokenService.issue("alice", "uid-1", "tenant-1");
        String tampered = token + "tampered";

        assertThat(tokenService.verify(tampered)).isNull();
    }

    @Test
    void issueWithEmptyRoles() {
        String token = tokenService.issue("carol", "uid-3", "tenant-3");

        Map<String, Object> payload = tokenService.verify(token);
        assertThat(payload).isNotNull();
        assertThat(payload.get("roles")).isInstanceOf(java.util.List.class);
    }
}