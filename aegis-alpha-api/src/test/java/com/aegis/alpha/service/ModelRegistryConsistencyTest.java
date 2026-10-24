package com.aegis.alpha.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Decision D4: one model list — orchestrator models.yaml == aegis.models.allowed-names == layout default. */
class ModelRegistryConsistencyTest {

    private static Set<String> csv(String text) {
        Set<String> out = new LinkedHashSet<String>();
        for (String part : Arrays.asList(text.split(","))) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void javaDefaultsEqualOrchestratorRegistry() throws Exception {
        Path registry = Paths.get("..", "aegis-alpha-orchestrator", "app", "core", "models.yaml");
        Assumptions.assumeTrue(Files.exists(registry), "orchestrator checkout not present");
        Map<String, Object> yaml;
        try (InputStream in = Files.newInputStream(registry)) {
            yaml = (Map<String, Object>) new Yaml().load(in);
        }
        Set<String> registryNames = ((Map<String, Object>) yaml.get("models")).keySet();

        assertThat(csv(ModelAllowlist.DEFAULT_ALLOWED)).isEqualTo(registryNames);

        String appYml = new String(Files.readAllBytes(Paths.get("src", "main", "resources", "application.yml")),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\$\\{AEGIS_ALPHA_ALLOWED_MODEL_NAMES:([^}]*)}").matcher(appYml);
        assertThat(m.find()).isTrue();
        assertThat(csv(m.group(1))).isEqualTo(registryNames);
        assertThat(new WorkflowValidationService().effectiveAllowedModels()).isEqualTo(registryNames);
    }

    @Test
    void workflowListMayOnlyNarrowTheRegistryList() {
        WorkflowValidationService narrowed = new WorkflowValidationService();
        narrowed.setAllowedModelNames("deepseek-v4-pro");
        narrowed.checkConsistency();
        assertThat(narrowed.effectiveAllowedModels()).containsExactly("deepseek-v4-pro");

        WorkflowValidationService widened = new WorkflowValidationService();
        widened.setAllowedModelNames("deepseek-v4-pro,gpt-unknown");
        assertThatThrownBy(widened::checkConsistency)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("gpt-unknown");
    }
}
