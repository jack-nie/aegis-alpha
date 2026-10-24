package com.aegis.alpha.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Decision D6: DEV_MODE / sample token are refused at startup when a production marker is active. */
class ProductionSafetyGuardTest {

    private static ProductionSafetyGuard guard(String env, String profile, boolean devMode, String token) {
        MockEnvironment environment = new MockEnvironment();
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        return new ProductionSafetyGuard(environment, env, devMode, token);
    }

    @Test
    void localDevModeIsAllowed() {
        guard("", null, true, ProductionSafetyGuard.SAMPLE_SERVICE_TOKEN).check();
        guard("local", "dev", true, "x").check();
    }

    @Test
    void devModeInProductionRefusesToStart() {
        assertThatThrownBy(() -> guard("production", null, true, "real-token").check())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DEV_MODE");
        assertThatThrownBy(() -> guard("", "prod", true, "real-token").check())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sampleTokenInProductionRefusesToStart() {
        assertThatThrownBy(() -> guard("PROD", null, false, " local-workflow-node-token ").check())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sample");
    }

    @Test
    void demoTokenSecretInProductionRefusesToStart() {
        ProductionSafetyGuard g = guard("production", null, false, "real-token");
        g.setTokenSecret(ProductionSafetyGuard.DEMO_TOKEN_SECRET);
        assertThatThrownBy(g::check).isInstanceOf(IllegalStateException.class).hasMessageContaining("TOKEN_SECRET");
        ProductionSafetyGuard local = guard("", null, false, "real-token");
        local.setTokenSecret(ProductionSafetyGuard.DEMO_TOKEN_SECRET);
        local.check();
    }

    @Test
    void cleanProductionStarts() {
        ProductionSafetyGuard ok = guard("production", "prod", false, "real-token");
        ok.check();
        assertThat(ok.problems()).isEmpty();
    }
}
