package com.aegis.alpha.service.help;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookServiceTest {

    @Test
    void parseFrontMatterAndKeywords() {
        String raw = "---\n"
                + "id: sample\n"
                + "title: Sample Title\n"
                + "keywords:\n"
                + "  - foo\n"
                + "  - bar keyword\n"
                + "deepLink: /x\n"
                + "---\n"
                + "\n"
                + "# Hello\n"
                + "Body text.\n";
        PlaybookService.Playbook playbook = PlaybookService.parse(raw, "sample.md");
        assertThat(playbook.getId()).isEqualTo("sample");
        assertThat(playbook.getTitle()).isEqualTo("Sample Title");
        assertThat(playbook.getDeepLink()).isEqualTo("/x");
        assertThat(playbook.getKeywords()).containsExactly("foo", "bar keyword");
        assertThat(playbook.getBodyMarkdown()).contains("# Hello");
    }

    @Test
    void loadsClasspathPlaybooksAndMatches() {
        PlaybookService service = new PlaybookService();
        service.reload();
        assertThat(service.list()).isNotEmpty();
        PlaybookService.Playbook hit = service.findBestMatch("什么是 DEGRADED？");
        assertThat(hit).isNotNull();
        assertThat(hit.getId()).isEqualTo("degraded-meaning");
    }
}
