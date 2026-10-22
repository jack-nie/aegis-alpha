package com.aegis.alpha.service.help;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** P2-1: keyword tokenizer emits CJK bigrams (aligned with orchestrator help_loop.tokenize). */
class KnowledgeServiceTokenizeTest {

    @Test
    void chineseQuestionProducesBigrams() {
        assertThat(KnowledgeService.tokenize("如何查看运行中心"))
                .containsExactly("如何", "何查", "查看", "看运", "运行", "行中", "中心");
    }

    @Test
    void mixedAsciiAndHanAreSplit() {
        assertThat(KnowledgeService.tokenize("run_id在哪看 workflow-ops"))
                .containsExactly("run_id", "在哪", "哪看", "workflow-ops");
        assertThat(KnowledgeService.tokenize("审")).containsExactly("审");
        assertThat(KnowledgeService.tokenize("")).isEmpty();
    }
}
