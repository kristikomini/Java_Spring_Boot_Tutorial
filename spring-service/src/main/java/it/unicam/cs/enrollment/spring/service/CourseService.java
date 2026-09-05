package it.unicam.cs.enrollment.spring.service;

import it.unicam.cs.enrollment.spring.domain.Course;
import it.unicam.cs.enrollment.spring.domain.EnrollmentStatus;
import it.unicam.cs.enrollment.spring.domain.Semester;
import it.unicam.cs.enrollment.spring.config.CacheConfig;
import it.unicam.cs.enrollment.spring.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.spring.repository.CourseRepository;
import it.unicam.cs.enrollment.spring.repository.EnrollmentRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads over the course catalogue.
 *
 * <p>Every method here is {@code readOnly = true}. That is not decoration on a
 * query service: it turns off Hibernate dirty checking for the whole persistence
 * context and marks the JDBC connection read-only, which is what allows a
 * replicated database to answer from a replica.
 */
@Service
@Transactional(readOnly = true)
public class CourseService {

    private static final List<EnrollmentStatus> OCCUPYING_STATUSES =
            List.of(EnrollmentStatus.ACTIVE, EnrollmentStatus.FAILED);

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final Clock clock;

    public CourseService(CourseRepository courseRepository,
                         EnrollmentRepository enrollmentRepository,
                         Clock clock) {
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.clock = clock;
    }

    public Page<Course> findByYearAndSemester(int academicYear, Semester semester,
                                              Pageable pageable) {
        return courseRepository.findByYearAndOptionalSemester(academicYear, semester, pageable);
    }

    /**
     * Cached, because the open-course list is read on every page load and
     * changes only when a course is created or its window is edited.
     *
     * <p>THE SUBTLETY THAT MAKES THIS ONE INTERESTING: the result depends on
     * {@code clock.instant()}, so it is time-varying data in a cache keyed on
     * nothing. A course whose window closes at 10:00 keeps being returned until
     * the five-minute entry expires. That is a real staleness window, and it is
     * acceptable here only because {@code Course.isEnrollmentOpen} is re-checked
     * inside the enrollment transaction - so the worst case is a course that
     * looks open and refuses the enrollment with a 409, not a seat sold outside
     * the window.
     *
     * <p>Being able to state that chain - what is stale, for how long, and what
     * stops it mattering - is the difference between using a cache and hoping.
     *
     * <p>{@code sync = true} collapses a cache stampede: when the entry expires
     * under load, one thread recomputes and the rest wait, instead of fifty
     * threads all running the same query against the database at once. It is a
     * one-word fix for a failure that only appears at exactly the moment you can
     * least afford it.
     */
    @Cacheable(cacheNames = CacheConfig.OPEN_COURSES, sync = true)
    public List<Course> findOpenForEnrollment() {
        return courseRepository.findOpenForEnrollment(clock.instant());
    }

    /**
     * Cached by id.
     *
     * <p>{@code unless = "#result == null"} is belt and braces here (the method
     * throws rather than returning null) but it is the habit worth forming:
     * caching a miss means the cache answers "not found" for five minutes after
     * the row is created.
     *
     * <p>THE SELF-INVOCATION TRAP APPLIES TO {@code @Cacheable} TOO, and this
     * catches more people than the transactional version because the symptom is
     * silent. {@code @Cacheable} is implemented by the same kind of proxy as
     * {@code @Transactional}: a call from another method inside THIS class goes
     * straight to the real object and never touches the cache. No error, no
     * warning - the cache simply never gets a hit, and someone spends an
     * afternoon wondering why the hit ratio is zero. Fieldbook chapter 11 has
     * the diagram; it is the same diagram.
     */
    @Cacheable(cacheNames = CacheConfig.COURSE_DETAIL, key = "#id", unless = "#result == null")
    public Course findByIdWithPrerequisites(Long id) {
        return courseRepository.findByIdWithPrerequisites(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Course", id));
    }

    /**
     * Evict when a course changes.
     *
     * <p>Written in the same class as the {@code @Cacheable} methods on purpose:
     * a cache and its invalidation are one decision, and splitting them across
     * files is how an application ends up serving a title somebody corrected
     * last Tuesday.
     *
     * <p>{@code allEntries = true} on the open-course list because a change to
     * any single course can add it to or remove it from that list, and there is
     * no key to evict selectively. The detail cache is evicted by id.
     *
     * <p>Nothing calls this yet - the write endpoints for courses are not ported
     * to this module. It is here because the alternative, adding the cache now
     * and the eviction "later", is precisely how the bug gets written.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.COURSE_DETAIL, key = "#courseId"),
            @CacheEvict(cacheNames = CacheConfig.OPEN_COURSES, allEntries = true)
    })
    public void evictCourse(Long courseId) {
        // Intentionally empty: the annotations are the behaviour. A method whose
        // whole body is an annotation looks odd the first time and is the normal
        // shape for a targeted eviction hook.
    }

    /**
     * Seat counts for a whole page of courses, in ONE query.
     *
     * <p>This method exists only to avoid an N+1, and it is the clearest example
     * of the pattern in the codebase. The obvious implementation - loop over the
     * courses, ask each one how full it is - costs one query per course, so a
     * page of 20 turns into 21 round trips. Grouping in the database costs one.
     *
     * <p>The {@code Object[]} unpacking is the price of a JPQL tuple. Each row is
     * {@code [courseId, count]}; both come back as Number because the JPQL type
     * is not carried through, which is why the casts go via {@code Number} rather
     * than straight to Long. Casting an Integer directly to Long here is a
     * ClassCastException waiting for a different database to return a narrower
     * type, and it is a real bug people ship.
     */
    public Map<Long, Long> occupiedSeatsFor(List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            // An empty IN clause is a syntax error in several databases, so the
            // guard is not defensive noise - it is required.
            return Collections.emptyMap();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : enrollmentRepository
                .countOccupiedSeatsByCourse(courseIds, OCCUPYING_STATUSES)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }
}
