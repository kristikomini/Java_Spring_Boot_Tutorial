package it.unicam.cs.enrollment.spring.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * ============================================================================
 * THE MDC DOES NOT CROSS A THREAD BOUNDARY, AND THIS IS THE FIX
 * ============================================================================
 * CorrelationIdFilter puts the id in the MDC. The MDC is a ThreadLocal. An
 * {@code @Async} method runs on a DIFFERENT thread, so it starts with an empty
 * context and every line it logs is unattributable.
 *
 * <p>That is not a bug in SLF4J, it is what ThreadLocal means - and it is the
 * follow-up question to "what is the MDC" that almost nobody has an answer
 * ready for. It bites the same way with parallel streams,
 * {@code CompletableFuture.supplyAsync}, and any executor you create yourself.
 *
 * <p>THE FIX is a TaskDecorator: capture the context on the SUBMITTING thread,
 * restore it on the EXECUTING thread, and clear it afterwards. Fifteen lines,
 * configured once, and every {@code @Async} method in the application keeps its
 * correlation id.
 *
 * <p>The {@code finally} block is not optional. Pool threads are reused, so a
 * task that leaves its context behind leaks one request id into the next task
 * that runs there - which is worse than having no id at all, because the log
 * then actively lies about which request a line belongs to.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * A NAMED, BOUNDED pool - not the default.
     *
     * <p>Without an {@code Executor} bean, {@code @Async} uses Spring default,
     * which historically was a SimpleAsyncTaskExecutor that creates a NEW THREAD
     * PER CALL and never reuses one. Under load that is unbounded thread
     * creation, and it is one of the classic ways a Spring application falls
     * over. Declaring the executor is not tuning; it is closing a hole.
     *
     * <p>THE QUEUE IS THE INTERESTING NUMBER. Tasks queue when all threads are
     * busy, and an UNBOUNDED queue means a slow downstream service silently
     * accumulates millions of pending notifications until the heap is gone -
     * with no error, because queueing looks like success. A bound of 100 means
     * the 101st task is REJECTED, loudly, while the system is still healthy
     * enough to say so. Failing fast beats failing later.
     *
     * <p>{@code CallerRunsPolicy} is the rejection policy worth knowing: instead
     * of throwing, the SUBMITTING thread runs the task itself. That applies
     * natural backpressure - the caller slows down because it is doing the work -
     * and is usually better than dropping. It is left at the default here so the
     * rejection is visible rather than hidden.
     */
    @Bean("notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        // A name that appears in every log line and every thread dump. A pool
        // called "task-1" tells you nothing at 3am.
        executor.setThreadNamePrefix("notify-");
        executor.setTaskDecorator(mdcPropagating());

        // On shutdown, let in-flight notifications finish rather than killing
        // them mid-request. Twenty seconds is a guess that should be shorter
        // than the container termination grace period, or the platform kills the
        // process while it is still waiting politely.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);

        executor.initialize();
        return executor;
    }

    /**
     * Carry the logging context across the thread boundary.
     *
     * <p>Read once at DECORATION time - which happens on the submitting thread,
     * while the context is still the right one. Reading it inside the returned
     * Runnable would read the executing thread context, which is exactly the
     * empty one we are trying to fix.
     */
    private TaskDecorator mdcPropagating() {
        return runnable -> {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    runnable.run();
                } finally {
                    // Pool threads are reused. Not clearing leaks one request id
                    // into the next task on this thread.
                    MDC.clear();
                }
            };
        };
    }
}
