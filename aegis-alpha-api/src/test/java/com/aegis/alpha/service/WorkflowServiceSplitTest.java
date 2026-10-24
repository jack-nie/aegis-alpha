package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** P3-10: approval logic and default layouts are extracted without changing WorkflowService's contract. */
class WorkflowServiceSplitTest {

    private WorkflowService service() {
        return new WorkflowService(mock(com.aegis.alpha.mapper.WorkflowMapper.class),
                mock(com.aegis.alpha.mapper.AgentMapper.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(LangChainGateway.class), mock(CacheService.class), mock(BacktestService.class),
                mock(AgentTraceService.class), new WorkflowValidationService(), mock(TokenService.class), false);
    }

    @Test
    void approverRolesSetterDelegatesToApprovalService() throws Exception {
        WorkflowService service = service();
        AuthenticatedPrincipal ops = new AuthenticatedPrincipal("u1", "ops", "t1", Arrays.asList("ops"));
        assertThat(service.isApprover(ops)).isFalse();
        service.setApproverRoles("ops");
        assertThat(service.isApprover(ops)).isTrue();

        Method setter = WorkflowService.class.getDeclaredMethod("setApproverRoles", String.class);
        assertThat(setter.getAnnotation(Value.class).value()).isEqualTo("${aegis.workflow.approver-roles:desk,admin}");
        assertThat(WorkflowService.APPROVAL_IN_PROGRESS).isEqualTo(WorkflowApprovalService.APPROVAL_IN_PROGRESS);
    }

    @Test
    void approvalServiceIsNotASpringBean() {
        assertThat(WorkflowApprovalService.class.getAnnotations()).isEmpty();
        assertThat(Modifier.isPublic(WorkflowApprovalService.class.getModifiers())).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultLayoutsKeepConstantsAndDigestClosers() {
        assertThat(WorkflowService.CURRENT_RESEARCH_LAYOUT_VERSION)
                .isEqualTo(DefaultWorkflowLayouts.CURRENT_RESEARCH_LAYOUT_VERSION).isEqualTo(4);
        WorkflowDefinition daily = new WorkflowDefinition();
        daily.setWorkflowKey("daily");
        daily.setName("Daily");
        Map<String, Object> layout = DefaultWorkflowLayouts.defaultLayout(daily);
        Map<String, Object> metadata = (Map<String, Object>) layout.get("metadata");
        assertThat(metadata.get("pattern")).isEqualTo(DefaultWorkflowLayouts.DIGEST_LAYOUT_PATTERN);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) layout.get("nodes");
        assertThat(nodes).anySatisfy(node -> assertThat(((Map<String, Object>) node.get("data")).get("handler"))
                .isEqualTo(DefaultWorkflowLayouts.DIGEST_AGGREGATE_HANDLER));
        assertThat(DefaultWorkflowLayouts.shouldRefreshResearchLayout("daily", DefaultWorkflowLayouts.emptyLayout("daily")))
                .isTrue();
        assertThat(DefaultWorkflowLayouts.shouldRefreshResearchLayout("daily", layout)).isFalse();
    }
}
