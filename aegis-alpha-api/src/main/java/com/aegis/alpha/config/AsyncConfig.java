package com.aegis.alpha.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for workflow work (streaming runs, async starts, layout write-back).
 *
 * <p>Injected by name, e.g. {@code @Qualifier(AsyncConfig.WORKFLOW_EXECUTOR) Executor executor}
 * (WorkflowController streaming, WorkflowService). Core/max/queue are configurable (P2-19,
 * {@code aegis.workflow.executor.*}); defaults keep the previous 4 / 16 / 200. Saturation applies
 * {@link ThreadPoolExecutor.CallerRunsPolicy} so bursts back-pressure the caller instead of dropping.</p>
 */
@Configuration
public class AsyncConfig {
    public static final String WORKFLOW_EXECUTOR = "workflowExecutor";

    @Bean(WORKFLOW_EXECUTOR)
    public ThreadPoolTaskExecutor workflowExecutor(
            @Value("${aegis.workflow.executor.core-size:4}") int coreSize,
            @Value("${aegis.workflow.executor.max-size:16}") int maxSize,
            @Value("${aegis.workflow.executor.queue-capacity:200}") int queueCapacity) {
        return buildWorkflowExecutor(coreSize, maxSize, queueCapacity);
    }

    static ThreadPoolTaskExecutor buildWorkflowExecutor(int coreSize, int maxSize, int queueCapacity) {
        int core = Math.max(1, coreSize);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(Math.max(core, maxSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix("workflow-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
