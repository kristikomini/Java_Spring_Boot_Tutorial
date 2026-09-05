package it.unicam.cs.enrollment.spring.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * ============================================================================
 * CACHING - the one optimisation everyone reaches for first, and the one that
 * causes the most bugs
 * ============================================================================
 * Fieldbook chapter 25 spends a chapter on where the time goes and never
 * introduces a cache, which leaves an obvious question unanswered. This is the
 * answer, with the conditions attached.
 *
 * <p>THE THREE QUESTIONS TO ANSWER BEFORE ADDING ONE. A cache is a correctness
 * risk traded for speed, and the trade is only worth it when all three have
 * answers:
 *
 * <p>1. IS THE DATA READ FAR MORE OFTEN THAN IT CHANGES? The course catalogue
 * is: read on every page load, edited a few times a semester. The seat count is
 * NOT - it changes on every enrollment, which is why nothing below caches it.
 *
 * <p>2. HOW STALE MAY IT BE? This is a product question, not a technical one,
 * and the honest version is "how long may a student see a course title that was
 * corrected five minutes ago". Five minutes, here. Nobody can answer this for
 * you, and a cache built without asking it is a bug with a schedule.
 *
 * <p>3. WHAT INVALIDATES IT? If the answer is "nothing, it expires", say so
 * deliberately. If it is "an edit", the eviction has to be written at the same
 * time as the cache, in the same commit - see CourseService.
 *
 * <p>LOCAL, NOT SHARED, AND THAT IS A REAL LIMITATION. Caffeine lives in this
 * process heap. Run three instances behind a load balancer and you have three
 * caches that disagree for up to five minutes, and an eviction on one does not
 * reach the other two. For a course catalogue that is fine and is the right
 * default: an in-process cache cannot go wrong in the ways a distributed one
 * can. When it stops being fine, the move is Redis, and the point of Spring
 * Cache is that the move is a dependency swap and a configuration block - the
 * {@code @Cacheable} annotations do not change. See application.yml.
 *
 * <p>WHY A CONFIGURATION CLASS AT ALL rather than the two lines of YAML Boot
 * accepts: because the per-cache TTL is a decision that deserves a comment next
 * to it, and because {@code recordStats()} is what makes the hit ratio visible
 * at /actuator/metrics/cache.gets. A cache you cannot measure is a cache you
 * cannot defend in a review.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Named constants, so a typo in an annotation is a compile error. */
    public static final String COURSE_DETAIL = "courseDetail";
    public static final String OPEN_COURSES = "openCourses";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(COURSE_DETAIL, OPEN_COURSES);
        manager.setCaffeine(Caffeine.newBuilder()
                // expireAfterWRITE, not expireAfterAccess. Access-based expiry
                // means a popular entry is never refreshed at all - it stays hot
                // and stays stale indefinitely, which is exactly the wrong
                // behaviour for a catalogue.
                .expireAfterWrite(5, TimeUnit.MINUTES)

                // A bound. An unbounded cache is a memory leak with a friendly
                // name: it grows until the heap does not fit, and the symptom is
                // GC pressure rather than anything that mentions caching.
                .maximumSize(500)

                // Publishes hit/miss counts to Micrometer, so the hit ratio is a
                // number rather than an opinion. A cache with a 3% hit rate is
                // pure overhead and should be deleted; you will not know which
                // kind you have without this line.
                .recordStats());
        return manager;
    }
}
