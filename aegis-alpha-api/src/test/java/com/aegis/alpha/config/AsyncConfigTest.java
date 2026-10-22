package com.aegis.alpha.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void workflowExecutorUsesConfiguredBoundsAndBackPressure() {
        ThreadPoolTaskExecutor executor = new AsyncConfig().workflowExecutor(2, 5, 7);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(5);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(7);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("workflow-");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void invalidSizesAreClampedToAWorkingPool() {
        ThreadPoolTaskExecutor executor = new AsyncConfig().workflowExecutor(0, 0, -1);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
