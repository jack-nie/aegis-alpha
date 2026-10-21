package com.aegis.alpha.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor reserved for workflow fan-out.
 *
 * <p>Not wired into any service yet. To adopt it later, inject by name, e.g.
 * {@code @Qualifier(AsyncConfig.WORKFLOW_EXECUTOR) Executor executor}, and submit
 * workflow/agent work through it instead of spawning ad-hoc threads. The pool is
 * bounded (4 core / 16 max / 200 queue) and applies {@link ThreadPoolExecutor.CallerRunsPolicy}
 * so bursts apply back-pressure to the caller instead of dropping tasks.</p>
 */
@Configuration
public class AsyncConfig {
    public static final String WORKFLOW_EXECUTOR = "workflowExecutor";

    @Bean(WORKFLOW_EXECUTOR)
    public ThreadPoolTaskExecutor workflowExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("workflow-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
