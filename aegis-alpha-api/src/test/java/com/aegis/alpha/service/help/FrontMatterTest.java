package com.aegis.alpha.service.help;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

/** P3-2: SnakeYAML front matter shared by playbooks and knowledge docs. */
class FrontMatterTest {

    @Test
    void parsesBlockAndFlowListsAndQuotedScalars() {
        FrontMatter fm = FrontMatter.parse("---\r\nid: \"x-1\"\r\ntitle: 'A: B'\r\nkeywords:\r\n  - 审批\r\n  - \"run center\"\r\n"
                + "allowedRoles: [ADMIN, OPS]\r\n---\r\n# Body\r\n", "x.md");

        assertThat(fm.string("id")).isEqualTo("x-1");
        assertThat(fm.string("title")).isEqualTo("A: B");
        assertThat(fm.list("keywords")).containsExactly("审批", "run center");
        assertThat(fm.list("allowedRoles")).containsExactly("ADMIN", "OPS");
        assertThat(fm.body().trim()).isEqualTo("# Body");
    }

    @Test
    void commaScalarListStillSupported() {
        FrontMatter fm = FrontMatter.parse("---\nkeywords: a, b ,c\n---\nbody", "y.md");
        assertThat(fm.list("keywords")).containsExactly("a", "b", "c");
    }

    @Test
    void malformedYamlKeepsBodyAndFallsBackToFilename() {
        PlaybookService.Playbook playbook = PlaybookService.parse("---\nid: [unclosed\n---\nbody text\n", "broken.md");
        assertThat(playbook.getId()).isEqualTo("broken");
        assertThat(playbook.getBodyMarkdown()).isEqualTo("body text");
    }

    @Test
    void noFrontMatterMeansWholeBody() {
        KnowledgeService.KnowledgeDoc doc = KnowledgeService.parse("# Title\ntext", "plain.md");
        assertThat(doc.getId()).isEqualTo("plain");
        assertThat(doc.getVisibility()).isEqualTo("public");
        assertThat(doc.getBodyMarkdown()).startsWith("# Title");
    }

    @Test
    void allBundledHelpDocsParseWithIdTitleAndKeywords() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:help/**/*.md");
        assertThat(resources).isNotEmpty();
        for (Resource resource : resources) {
            String raw;
            try (InputStream in = resource.getInputStream(); Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name())) {
                raw = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
            }
            if (!raw.startsWith("---")) {
                continue;
            }
            FrontMatter fm = FrontMatter.parse(raw, resource.getFilename());
            assertThat(fm.string("id")).as(resource.getFilename()).isNotNull();
            assertThat(fm.string("title")).as(resource.getFilename()).isNotNull();
            assertThat(fm.list("keywords")).as(resource.getFilename()).isNotEmpty();
        }
    }
}
