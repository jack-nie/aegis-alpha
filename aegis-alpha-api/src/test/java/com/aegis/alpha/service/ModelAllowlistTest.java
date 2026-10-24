package com.aegis.alpha.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelAllowlistTest {
    @Test
    void defaultsMirrorOrchestratorRegistry() {
        ModelAllowlist list = ModelAllowlist.defaults();
        assertThat(list.names()).containsExactly("deepseek-v4-flash", "deepseek-v4-pro");
        assertThat(list.isAllowed(" deepseek-v4-pro ")).isTrue();
        assertThat(list.isAllowed("gpt-4")).isFalse();
        assertThat(list.isAllowed(null)).isFalse();
        assertThat(list.isAllowed("")).isFalse();
    }

    @Test
    void configuredListReplacesDefaultsAndBlankKeepsThem() {
        assertThat(ModelAllowlist.of(" a , b ,").names()).containsExactly("a", "b");
        assertThat(ModelAllowlist.of(" , ").names()).containsExactly("deepseek-v4-flash", "deepseek-v4-pro");
    }
}
