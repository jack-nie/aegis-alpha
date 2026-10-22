package com.aegis.alpha.service.help;

import com.aegis.alpha.service.LangChainGateway;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P2-6: golden20 in three classifier modes. The LLM-off mode is {@link Golden20RoutingTest};
 * the nested classes re-run every inherited golden check (accuracy >= 0.9, safety 1.0) with a
 * stub that always names a workflow, at high and at low confidence.
 */
class HelpRoutingGoldenTest {

    @Test
    void classpathFixtureIsTheSpecFixture() throws Exception {
        Path spec = null;
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && spec == null) {
            Path candidate = dir.resolve("docs/superpowers/specs/help-agent-contracts/fixtures/golden20.json");
            if (Files.exists(candidate)) {
                spec = candidate;
            }
            dir = dir.getParent();
        }
        assertThat(spec).as("spec golden20.json").isNotNull();
        byte[] copy = Files.readAllBytes(Paths.get("src/test/resources/help/golden20.json"));
        assertThat(copy).as("src/test/resources/help/golden20.json must equal the spec fixture")
                .isEqualTo(Files.readAllBytes(spec));
    }

    private static Map<String, Object> llmAnswer(String workflowKey, double confidence) {
        Map<String, Object> answer = new HashMap<String, Object>();
        answer.put("workflowKey", workflowKey);
        answer.put("confidence", confidence);
        return answer;
    }

    @Nested
    class LlmAlwaysReturnsWorkflow extends Golden20RoutingTest {
        @Override
        protected void stubClassifier(LangChainGateway gateway) {
            when(gateway.classifyIntent(any(), any())).thenReturn(llmAnswer("deep_dive", 0.95));
        }

        @Override
        protected String modeName() {
            return "llm-always-workflow";
        }
    }

    @Nested
    class LlmLowConfidence extends Golden20RoutingTest {
        @Override
        protected void stubClassifier(LangChainGateway gateway) {
            when(gateway.classifyIntent(any(), any())).thenReturn(llmAnswer("deep_dive", 0.2));
        }

        @Override
        protected String modeName() {
            return "llm-low-confidence";
        }
    }
}
