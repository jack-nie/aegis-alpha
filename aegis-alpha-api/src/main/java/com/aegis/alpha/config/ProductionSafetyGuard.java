package com.aegis.alpha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Decision D6: dev conveniences (AEGIS_ALPHA_DEV_MODE, the documented sample service token) are for the
 * local launcher only. When a production marker is active (AEGIS_ALPHA_ENV=prod|production or a Spring
 * profile prod|production) the application refuses to start instead of logging a warning.
 */
@Component
public class ProductionSafetyGuard {
    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyGuard.class);
    /** Same documented sample value as the orchestrator's EXAMPLE_SERVICE_TOKEN. */
    public static final String SAMPLE_SERVICE_TOKEN = "local-workflow-node-token";
    /** application.yml fallback for aegis.token-secret (demo only). */
    public static final String DEMO_TOKEN_SECRET = "local-aegis-demo-secret";

    private final Environment environment;
    private final String env;
    private final boolean devMode;
    private final String serviceToken;
    private String tokenSecret = "";

    public ProductionSafetyGuard(Environment environment,
                                 @Value("${aegis.env:}") String env,
                                 @Value("${aegis.dev-mode:false}") boolean devMode,
                                 @Value("${aegis.dify.node-execution-token:}") String serviceToken) {
        this.environment = environment;
        this.env = env;
        this.devMode = devMode;
        this.serviceToken = serviceToken;
    }

    @Value("${aegis.token-secret:}")
    void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret == null ? "" : tokenSecret;
    }

    static boolean isProductionMarker(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "prod".equals(v) || "production".equals(v);
    }

    boolean isProduction() {
        if (isProductionMarker(env)) {
            return true;
        }
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        for (String profile : Arrays.asList(profiles)) {
            if (isProductionMarker(profile)) {
                return true;
            }
        }
        return false;
    }

    /** Problems that must stop a production start (empty outside production). */
    List<String> problems() {
        List<String> out = new ArrayList<String>();
        if (!isProduction()) {
            return out;
        }
        if (devMode) {
            out.add("AEGIS_ALPHA_DEV_MODE=true is not allowed in production");
        }
        if (serviceToken != null && SAMPLE_SERVICE_TOKEN.equals(serviceToken.trim())) {
            out.add("the sample AEGIS_ALPHA_NODE_EXECUTION_TOKEN is not allowed in production");
        }
        if (DEMO_TOKEN_SECRET.equals(tokenSecret.trim())) {
            out.add("the demo AEGIS_ALPHA_TOKEN_SECRET is not allowed in production");
        }
        return out;
    }

    @PostConstruct
    void check() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            log.error("[FATAL] refusing to start: {}", problems);
            throw new IllegalStateException("Production safety check failed: " + problems);
        }
    }
}
