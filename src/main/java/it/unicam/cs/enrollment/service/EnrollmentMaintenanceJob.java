package it.unicam.cs.enrollment.service;

import it.unicam.cs.enrollment.domain.model.StudentStatus;
import it.unicam.cs.enrollment.repository.EnrollmentRepository;
import it.unicam.cs.enrollment.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * Scheduled housekeeping.
 *
 * <h2>How {@code @Scheduled} actually works</h2>
 * It is the same proxy machinery as {@code @Transactional}: a post-processor
 * finds annotated methods on Spring beans at startup and registers them with a
 * {@code TaskScheduler}. Two consequences follow, and both surprise people.
 *
 * <ol>
 *   <li>The method must be on a SPRING BEAN. A {@code @Scheduled} method in a
 *       class you construct with {@code new} never runs, and nothing warns
 *       you.</li>
 *   <li>The method takes no arguments and should return void. There is no
 *       caller to supply parameters or read a result.</li>
 * </ol>
 *
 * <p>The scheduler is separate from the {@code @Async} executor in
 * {@code AsyncConfig} and, by default, has a pool of exactly one thread. Every
 * scheduled method in the application shares it, so a job that takes ten
 * minutes delays every other job for ten minutes. Raising
 * {@code spring.task.scheduling.pool.size} is the fix, and knowing that the
 * default is 1 is the part worth carrying to an interview.
 *
 * <h2>Cron in Spring has SIX fields</h2>
 * <pre>
 *   second minute hour day-of-month month day-of-week
 *   0      0      3    *            *     *              -&gt; 03:00 every day
 * </pre>
 * Unix cron has five and begins at minutes, so an expression copied from a
 * crontab is shifted one place and fires at the wrong time. Spring also accepts
 * macros - {@code @daily}, {@code @hourly} - and
 * {@code fixedDelay}/{@code fixedRate} for "every N", where {@code fixedDelay}
 * measures from the END of the previous run and {@code fixedRate} from its
 * start. Use {@code fixedRate} on a job that can overtake itself and you get
 * overlapping executions.
 *
 * <h2>What Spring does not solve: running more than one instance</h2>
 * Every replica has its own scheduler, so a two-instance deployment runs this
 * sweep twice. For a bulk UPDATE that is merely wasteful; for anything that
 * sends mail or moves money it is a bug. The real answers are a distributed
 * lock (ShedLock), a clustered scheduler (Quartz with a JDBC job store), or an
 * external trigger such as a Kubernetes CronJob calling an endpoint. Knowing
 * that this is a genuinely hard problem, rather than assuming the annotation
 * handles it, is the takeaway.
 */
@Component
public class EnrollmentMaintenanceJob {

    private static final Logger LOG = LoggerFactory.getLogger(EnrollmentMaintenanceJob.class);

    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final Clock clock;

    public EnrollmentMaintenanceJob(EnrollmentRepository enrollmentRepository,
                                    StudentRepository studentRepository,
                                    Clock clock) {
        this.enrollmentRepository = enrollmentRepository;
        this.studentRepository = studentRepository;
        this.clock = clock;
    }

    /**
     * Nightly sweep at 03:00: withdraws enrollments left ACTIVE from previous
     * academic years.
     *
     * <h3>{@code REQUIRES_NEW}</h3>
     * A scheduled method has no caller and therefore no inbound transaction, so
     * this is really documentation of intent - but stating it makes the
     * boundary obvious to the next reader, and it is what the bulk update in
     * the repository needs: a short transaction of its own.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeStaleEnrollments() {
        int currentAcademicYear = clock.instant().atZone(ZoneOffset.UTC).getYear();

        LOG.info("Starting stale-enrollment sweep for academic years before {}", currentAcademicYear);

        int affected = enrollmentRepository.closeStaleEnrollments(currentAcademicYear, clock.instant());

        // Logging the count matters. A job that silently does nothing looks
        // exactly like a job that is broken; a job that reports "0 rows" tells
        // you it ran and there was nothing to do.
        LOG.info("Stale-enrollment sweep finished: {} enrollment(s) withdrawn", affected);
    }

    /**
     * A lightweight heartbeat that also emits basic counts.
     *
     * <p>Runs every five minutes so that you can actually SEE the scheduler
     * working while the application is up - watch for it in
     * {@code docker compose logs -f app}.
     *
     * <p>In a production system these numbers would go to Micrometer and be
     * scraped by Prometheus rather than written to the log, so they could be
     * graphed and alerted on. {@code ReportingController} does exactly that for
     * the real statistics; logging them here is the zero-dependency version of
     * the same idea.
     */
    @Scheduled(cron = "0 */5 * * * *")
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void reportStatistics() {
        long active = studentRepository.countByStatus(StudentStatus.ACTIVE);
        long suspended = studentRepository.countByStatus(StudentStatus.SUSPENDED);

        LOG.info("[METRICS] students.active={} students.suspended={}", active, suspended);
    }
}
