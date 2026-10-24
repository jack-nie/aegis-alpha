package com.aegis.alpha.service.help;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P3-3: Lombok keeps the exact accessor names the hand-written HelpIntent exposed. */
class HelpIntentAccessorsTest {

    @Test
    void resultAccessorNamesAreUnchanged() {
        assertThat(publicMethods(HelpIntent.Result.class)).contains(
                "getWorkflowKey", "setWorkflowKey", "getTicker", "setTicker", "getConfidence", "setConfidence",
                "getSource", "setSource", "getReason", "setReason", "getDomain", "setDomain",
                "getComplexity", "setComplexity", "getRouteAction", "setRouteAction",
                "isNeedsClarification", "setNeedsClarification", "getClarificationPrompt", "setClarificationPrompt",
                "getEntities", "setEntities", "isCacheable", "setCacheable", "getPlaybookId", "setPlaybookId",
                "getData", "setData", "toEnvelopeFields");
    }

    @Test
    void entitiesAndPageContextAccessorsAreUnchanged() {
        assertThat(publicMethods(HelpIntent.Entities.class)).contains(
                "getRunId", "setRunId", "getTicker", "setTicker", "getPath", "setPath",
                "getPortfolioId", "setPortfolioId", "getRecommendationId", "setRecommendationId", "toMap");
        List<String> page = publicMethods(HelpIntent.PageContext.class);
        assertThat(page).contains("getPath", "getRunId", "getWorkflowKey", "getTicker", "getRecommendationId", "getPortfolioId");
        assertThat(page).noneMatch(name -> name.startsWith("set"));
    }

    @Test
    void nullNormalizingAccessorsKeepTheirBehaviour() {
        HelpIntent.Result result = HelpIntent.Result.empty();
        result.setEntities(null);
        result.setData(null);
        assertThat(result.getEntities()).isNotNull();
        assertThat(result.getData()).isEmpty();
        assertThat(result.isNeedsClarification()).isFalse();
        assertThat(result.getSource()).isEqualTo("none");

        Map<String, Object> ctx = new LinkedHashMap<String, Object>();
        ctx.put("run_id", " r1 ");
        assertThat(HelpIntent.PageContext.fromMap(ctx).getRunId()).isEqualTo("r1");
    }

    private static List<String> publicMethods(Class<?> type) {
        List<String> names = new ArrayList<String>();
        for (Method method : type.getMethods()) {
            names.add(method.getName());
        }
        return names;
    }
}
