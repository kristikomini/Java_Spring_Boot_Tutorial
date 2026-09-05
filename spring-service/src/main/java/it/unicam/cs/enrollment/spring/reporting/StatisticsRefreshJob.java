package it.unicam.cs.enrollment.spring.reporting;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ============================================================================
 * THE BATCH JOB - "batch notturni" on an Italian advert
 * ============================================================================
 * Fieldbook chapter 26 covers {@code @Schedule} in Jakarta EE and stops at work
 * nobody requested. This is the same idea in Spring, doing what an
 * analytics-flavoured role actually asks for: recomputing a report on a
 * schedule.
 *
 * <pre>
 *   Jakarta EE                          Spring
 *   ---------------------------------   ---------------------------------
 *   {@literal @}Singleton + {@literal @}Schedule(hour="*")     {@literal @}Component + {@literal @}Scheduled
 *   the EJB container timer service     a ThreadPoolTaskScheduler
 *   {@literal @}Lock(READ/WRITE)                    nothing - see the overlap note below
 * </pre>
 *
 * <p>THIS CLASS SCHEDULES AND OBSERVES. IT DOES NOT DO THE WORK.
 * {@link StatisticsRefreshService} owns the transaction, and the split is not
 * taste: putting the {@code @Transactional} method in this class would mean
 * calling it on {@code this}, which bypasses the proxy and silently runs with no
 * transaction at all. That file explains it at length, and it is worth reading
 * before writing any scheduled job.
 *
 * <p>WHY NOT SPRING BATCH. It is the name on the adverts, and it is the right
 * tool when you need restart from the failed chunk, a job repository recording
 * every execution, per-item skip and retry policies, or partitioned parallel
 * steps. That is a serious framework with nine tables of its own schema, and
 * reaching for it to run one aggregate query is how a codebase acquires
 * infrastructure nobody uses.
 *
 * <p>The honest junior answer, and the one worth being able to give: this job is
 * a single set-based statement, so it needs a scheduler and a transaction, not a
 * batch framework. Spring Batch earns its place when the work is item-by-item,
 * long enough that restarting from the beginning is unacceptable, or needs an
 * auditable record of every run. Knowing when NOT to use the thing named on the
 * advert is a better signal than having used it.
 */
@Component
public class StatisticsRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(StatisticsRefreshJob.class);

    private final StatisticsRefreshService refreshService;
    private final MeterRegistry meterRegistry;

    public StatisticsRefreshJob(StatisticsRefreshService refreshService,
                                MeterRegistry meterRegistry) {
        this.refreshService = refreshService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Every ten minutes here; 03:00 in a real deployment, which would be
     * {@code @Scheduled(cron = "0 0 3 * * *")}.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}, and the difference is
     * the trap. {@code fixedRate} starts a run every N milliseconds regardless of
     * whether the previous one finished, so a job that grows slower than its
     * interval piles runs on top of each other until something falls over.
     * {@code fixedDelay} waits N milliseconds AFTER the previous run completed, so
     * a slow job simply runs less often. For anything whose duration depends on
     * data volume - which is every batch job - fixedDelay is the safe default.
     *
     * <p>{@code initialDelay} keeps it clear of startup. A job firing while the
     * connection pool is still filling makes a slow start slower and can fail a
     * readiness probe.
     *
     * <p>THE PART THAT BREAKS ON A SECOND SERVER, which chapter 26 raises and
     * chapter 33 raises again from Kubernetes: this runs on EVERY instance.
     * Three replicas means three concurrent refreshes computing identical numbers
     * and fighting over the same rows. Nothing here prevents it.
     *
     * <p>The standard fixes are a database lock (ShedLock is the usual library),
     * a leader election, or moving the trigger outside the application so exactly
     * one instance is called. Leaving it unsolved is not an oversight - it is the
     * honest state of a single-instance application, and papering over it would
     * hide the problem the deployment chapter needs to raise.
     */
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT10M")
    public void refresh() {
        // The job gets a correlation id of its own, so its log lines are as
        // greppable as a request. CorrelationIdFilter only ever runs for HTTP,
        // so without this the MDC is empty on a scheduler thread and every line
        // the job writes is unattributable.
        MDC.put("correlationId", "job-" + UUID.randomUUID().toString().substring(0, 4));

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            int written = refreshService.refreshAll();

            // A timer named after the job, so /actuator/prometheus can answer
            // "is the nightly job getting slower" - the question you want
            // answered BEFORE it starts overrunning its window.
            sample.stop(meterRegistry.timer("enrollment.statistics.refresh",
                    "outcome", "success"));
            meterRegistry.gauge("enrollment.statistics.rows", written);

        } catch (RuntimeException e) {
            sample.stop(meterRegistry.timer("enrollment.statistics.refresh",
                    "outcome", "failure"));

            // CAUGHT AND LOGGED, NOT RETHROWN, deliberately.
            //
            // An exception escaping a @Scheduled method is logged by the
            // scheduler and the schedule CONTINUES - Spring does not stop
            // scheduling because one run failed. Catching it here adds what the
            // default does not: a metric tagged outcome=failure, which an alert
            // can watch. "The job has not succeeded in six hours" is a
            // monitorable statement; "an exception appeared in the log" is not.
            //
            // Catching it here is safe ONLY because the transaction lives in the
            // other bean. Had @Transactional been on this method, swallowing the
            // exception would have COMMITTED the half-finished work.
            log.error("Statistics refresh failed; the previous numbers are still in place", e);

        } finally {
            // A scheduler thread is pooled and reused, so leaving the id behind
            // leaks it into whatever runs next on this thread. Same rule as the
            // HTTP filter, different pool.
            MDC.remove("correlationId");
        }
    }
}
