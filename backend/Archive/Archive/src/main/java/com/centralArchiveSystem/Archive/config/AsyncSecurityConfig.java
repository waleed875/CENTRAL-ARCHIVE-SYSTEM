package com.centralArchiveSystem.Archive.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

/** Capture each submitting caller and restore the worker context after every task. */
@Configuration
public class AsyncSecurityConfig implements AsyncConfigurer {
    @Bean(name = {"applicationTaskExecutor", "archiveWorkerPool"})
    public ThreadPoolTaskExecutor archiveWorkerPool() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(8);
        pool.setMaxPoolSize(8);
        pool.setThreadNamePrefix("archive-worker-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        return pool;
    }

    @Override
    public Executor getAsyncExecutor() {
        return new DelegatingSecurityContextAsyncTaskExecutor(archiveWorkerPool());
    }
}
