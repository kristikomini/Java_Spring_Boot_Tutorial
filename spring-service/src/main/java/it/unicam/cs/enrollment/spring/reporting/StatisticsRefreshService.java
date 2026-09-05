package it.unicam.cs.enrollment.spring.reporting;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 * A SEPARATE BEAN, BECAUSE OF THE SELF-INVOCATION TRAP
 * ============================================================================
 * This class exists for one reason, and it is the most useful accident in the
 * module. The first version of StatisticsRefreshJob had the scheduling, the
 * metrics and a @Transactional doRefresh() all in ONE class, with refresh()
 * calling doRefresh() directly.
 *
 * <p>THAT DOES NOT WORK, and it fails silently.
 *
 * <p>@Transactional is implemented with a PROXY. The bean injected everywhere
 * else is not the object you wrote, it is a wrapper that opens a transaction and
 * then delegates. A call from one method to another INSIDE the same object never
 * touches the wrapper - `this.doRefresh()` goes straight to the real method. No
 * transaction is opened, no error is raised, and nothing in the log mentions it.
 * The job appears to work; the write is simply not in the transaction you
 * declared, so the delete and the inserts are no longer atomic and a failure
 * halfway through leaves the report table empty.
 *
 * <p>THE FIX IS ALWAYS THE SAME: move the transactional method to a different
 * bean, so the call goes through the container and hits the proxy. That is the
 * whole reason this file exists, and splitting the two also happens to be
 * better design - one class schedules and observes, the other does the work and
 * owns the transaction.
 *
 * <p>The same trap applies to @Cacheable, @Async, @Retryable and @PreAuthorize.
 * It is the single most valuable Spring internal for a junior to understand,
 * because it explains a whole family of "the annotation does nothing" bugs, and
 * it is identical in Jakarta EE - fieldbook chapter 11 has the diagram.
 *
 * <p>The other fixes you will see, and why they are worse: self-injecting the
 * bean into itself (works, and is confusing to read), AopContext.currentProxy()
 * (works, needs exposeProxy=true, and couples your code to Spring AOP), and
 * switching to AspectJ weaving (works everywhere, and adds a build step nobody
 * on the team understands). Two beans is the answer that needs no explanation.
 */
@Service
public class StatisticsRefreshService {

    private static final Logger log = LoggerFactory.getLogger(StatisticsRefreshService.class);

    /**
     * How many rows go to the database per round trip.
     *
     * <p>It matches {@code hibernate.jdbc.batch_size} in application.yml on
     * purpose. Flushing in chunks larger than the JDBC batch gains nothing, and
     * chunks much smaller than it waste the batching entirely. They are two
     * halves of one setting, and they drift apart the moment somebody tunes one
     * without knowing about the other.
     */
    private static final int CHUNK_SIZE = 30;

    private final ReportingRepository reportingRepository;
    private final CourseStatisticsRepository statisticsRepository;
    private final Clock clock;

    /**
     * The raw EntityManager, alongside the repositories.
     *
     * <p>Needed for one thing only: {@code clear()}. JpaRepository has flush()
     * but no clear(), so a bulk job that wants to empty the persistence context
     * between chunks has to reach past the repository abstraction.
     *
     * <p>Worth noticing rather than hiding. Spring Data covers the common cases
     * extremely well and deliberately does not expose everything JPA can do;
     * when you need the rest, you inject the EntityManager and use it. Fighting
     * to express a bulk operation through repository methods alone produces
     * worse code than admitting the abstraction has an edge.
     */
    private final EntityManager entityManager;

    public StatisticsRefreshService(ReportingRepository reportingRepository,
                                    CourseStatisticsRepository statisticsRepository,
                                    Clock clock,
                                    EntityManager entityManager) {
        this.reportingRepository = reportingRepository;
        this.statisticsRepository = statisticsRepository;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    /**
     * Recompute every course statistic, in one transaction.
     *
     * <p>{@code Propagation.REQUIRED} is the default and is stated explicitly
     * because of the thing chapter 26 raises: a scheduled job has NO CALLER, so
     * there is no ambient transaction to join. Whatever this method declares is
     * what it gets, which is different from every other transactional method in
     * the application, all of which are reached from an HTTP request.
     *
     * <p>{@code timeout} is the setting most batch jobs should have and almost
     * none do. Without it a job that meets a pathological query holds a
     * connection and a transaction indefinitely, and the first symptom is the
     * connection pool exhausting for the HTTP traffic that shares it - so a
     * reporting job takes down the enrollment endpoint.
     */
    @Transactional(propagation = Propagation.REQUIRED, timeout = 120)
    public int refreshAll() {
        Instant computedAt = clock.instant();

        // ONE query, computed entirely in the database. See ReportingRepository.
        List<CourseStatisticsRow> rows = reportingRepository.computeAllCourseStatistics();

        // DELETE THEN INSERT, inside one transaction.
        //
        // Crude, and correct at this size. It is atomic - a reader sees either
        // the whole old snapshot or the whole new one, never a half-written
        // mixture - and it removes rows for courses that no longer exist, which
        // an upsert-only strategy silently leaves behind forever.
        //
        // It stops being right when the table is large enough that the delete is
        // expensive or the write lock is held too long. The next answers are:
        // write into a second table and swap them, or a real upsert
        // (ON CONFLICT DO UPDATE). Knowing the ORDER of those three is more
        // useful than knowing any one of them.
        //
        // deleteAllInBatch(), not deleteAll(): deleteAll() loads every entity
        // and issues one DELETE per row. deleteAllInBatch() issues a single
        // DELETE FROM. Chapter 26 calls this going around the persistence
        // context, and the caveat is the same - it bypasses cascades and
        // lifecycle callbacks, which is fine here because this table has neither.
        statisticsRepository.deleteAllInBatch();

        List<CourseStatistics> chunk = new ArrayList<>(CHUNK_SIZE);
        int written = 0;

        for (CourseStatisticsRow row : rows) {
            chunk.add(CourseStatistics.from(row, computedAt));
            if (chunk.size() == CHUNK_SIZE) {
                written += flushChunk(chunk);
            }
        }
        written += flushChunk(chunk);

        log.info("Statistics refreshed: {} course(s) as of {}", written, computedAt);
        return written;
    }

    /**
     * Write one chunk and forget it.
     *
     * <p>{@code saveAll} does NOT batch by itself - it is a loop over save().
     * What makes the INSERTs batch is {@code hibernate.jdbc.batch_size} plus a
     * flush boundary, and flushing every CHUNK_SIZE rows is what provides the
     * boundary.
     *
     * <p>The {@code clear()} is the half people leave out. Without it the
     * persistence context keeps every entity written so far, so memory grows for
     * the whole job AND Hibernate dirty-checks all of them at every subsequent
     * flush - which makes an O(n) job O(n squared). On a few hundred rows nobody
     * notices; on a hundred thousand it is the difference between a minute and
     * an OutOfMemoryError.
     */
    private int flushChunk(List<CourseStatistics> chunk) {
        if (chunk.isEmpty()) {
            return 0;
        }
        statisticsRepository.saveAll(chunk);
        statisticsRepository.flush();
        entityManager.clear();
        int size = chunk.size();
        chunk.clear();
        return size;
    }
}
