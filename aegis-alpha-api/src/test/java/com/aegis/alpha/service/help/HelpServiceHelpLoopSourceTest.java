package com.aegis.alpha.service.help;

import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.service.LangChainGateway;
import com.aegis.alpha.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1-4: the local help loop is the single source of retrieval; remote may only re-draft. */
class HelpServiceHelpLoopSourceTest {

    private static final String QUESTION = "如何运行 published 工作流 操作步骤";

    private LangChainGateway gateway;
    private HelpService helpService;

    @BeforeEach
    void setUp() {
        gateway = mock(LangChainGateway.class);
        KnowledgeService knowledge = new KnowledgeService();
        knowledge.reload();
        PlaybookService playbooks = new PlaybookService();
        playbooks.reload();
        helpService = new HelpService(playbooks, mock(WorkflowService.class), mock(WorkflowMapper.class),
                knowledge, new HelpLoopService(knowledge), gateway);
    }

    @Test
    void defaultModeNeverCallsRemoteAndUsesLocalSources() {
        Map<String, Object> answer = helpService.answerFromHelpLoop("t", QUESTION, null, Collections.<String>emptyList());

        verify(gateway, never()).executeHelp(anyString(), anyList(), any(), anyList());
        assertThat(answer.get("provider")).isNotEqualTo("help-loop-orch");
        assertThat(sourceIds(answer)).isNotEmpty();
    }

    @Test
    void onlineAndOfflineReturnTheSameSources() {
        helpService.setRemoteGenerationEnabled(true);
        when(gateway.executeHelp(anyString(), anyList(), any(), anyList())).thenReturn(null);
        Map<String, Object> offline = helpService.answerFromHelpLoop("t", QUESTION, null, Collections.<String>emptyList());

        Map<String, Object> remote = new LinkedHashMap<String, Object>();
        remote.put("ok", true);
        remote.put("content", "remote draft");
        remote.put("sourceRefs", Collections.singletonList(Collections.singletonMap("id", "stub-corpus-doc")));
        when(gateway.executeHelp(anyString(), anyList(), any(), anyList())).thenReturn(remote);
        Map<String, Object> online = helpService.answerFromHelpLoop("t", QUESTION, null, Collections.<String>emptyList());

        assertThat(sourceIds(online)).isEqualTo(sourceIds(offline)).doesNotContain("stub-corpus-doc");
        assertThat(online.get("content")).isEqualTo("remote draft");
        assertThat(online.get("provider")).isEqualTo("help-loop-orch");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<Map<String, Object>>> chunks = ArgumentCaptor.forClass((Class) List.class);
        verify(gateway, org.mockito.Mockito.atLeastOnce()).executeHelp(anyString(), anyList(), any(), chunks.capture());
        assertThat(chunks.getValue()).isNotEmpty();
    }

    @Test
    void remoteNotOkFallsBackToLocalAnswer() {
        helpService.setRemoteGenerationEnabled(true);
        Map<String, Object> local = helpService.answerFromHelpLoop("t", QUESTION, null, Collections.<String>emptyList());
        Map<String, Object> remote = new LinkedHashMap<String, Object>();
        remote.put("ok", false);
        remote.put("content", "escalated text");
        when(gateway.executeHelp(anyString(), anyList(), any(), anyList())).thenReturn(remote);

        Map<String, Object> answer = helpService.answerFromHelpLoop("t", QUESTION, null, Collections.<String>emptyList());

        assertThat(answer.get("content")).isEqualTo(local.get("content"));
        assertThat(answer.get("provider")).isEqualTo(local.get("provider"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sourceIds(Map<String, Object> answer) {
        List<Object> ids = new java.util.ArrayList<Object>();
        for (Object ref : (List<Object>) answer.get("sourceRefs")) {
            ids.add(((Map<String, Object>) ref).get("id"));
        }
        return ids;
    }
}
