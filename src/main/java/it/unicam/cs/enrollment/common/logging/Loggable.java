package it.unicam.cs.enrollment.common.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A marker annotation: put {@code @Loggable} on a class or a method and every
 * invocation is timed and logged.
 *
 * <h2>The problem this solves</h2>
 * Timing and logging are CROSS-CUTTING CONCERNS: needed in many places, related
 * to none of them. Written by hand, every service method turns into
 * <pre>
 *   long start = System.nanoTime();
 *   log.debug("entering enroll({}, {})", studentId, courseId);
 *   try {
 *       ... three lines of actual business logic ...
 *   } finally {
 *       log.debug("exiting enroll in {}ms", elapsed);
 *   }
 * </pre>
 * The business logic drowns. This is what Aspect-Oriented Programming (AOP) was
 * invented for, and Spring ships it as {@code spring-boot-starter-aop}.
 *
 * <h2>How the two pieces fit together</h2>
 * <ol>
 *   <li>This annotation - a plain marker, with no logic in it at all.</li>
 *   <li>{@link LoggingAspect} - the behaviour, an {@code @Aspect} whose
 *       pointcut matches anything carrying this annotation.</li>
 * </ol>
 *
 * <h2>Why this matters beyond logging</h2>
 * Spring implements the annotation by creating a PROXY around your bean: the
 * container hands callers an object that wraps yours, runs the advice, and
 * delegates. {@code @Transactional}, {@code @Cacheable}, {@code @Async} and
 * {@code @PreAuthorize} all work exactly this way.
 *
 * <p>Which explains the single most common Spring bug there is: SELF-INVOCATION.
 * If a method inside the class calls another method on {@code this}, the call
 * never leaves the object, so it never passes through the proxy, so the
 * annotation does nothing. A {@code @Transactional} method called from a
 * neighbouring method in the same class runs with NO transaction, silently.
 * Understanding this one aspect is what makes that behaviour obvious rather
 * than mystifying.
 *
 * <p>{@code @Inherited} means a subclass of an annotated class is advised too.
 */
@Inherited
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Loggable {

    /**
     * Log method arguments as well as timings.
     *
     * <p>Defaults to {@code false} ON PURPOSE. Arguments routinely contain
     * personal data, passwords or tokens, and logs are copied to systems with
     * far weaker access controls than your database. "Log everything and filter
     * later" is how organisations end up with credentials in their log
     * aggregator. Opt in per method, deliberately.
     */
    boolean logArguments() default false;

    /**
     * Calls slower than this (in milliseconds) are logged at WARN instead of
     * DEBUG. A cheap, always-on performance tripwire.
     */
    long slowCallThresholdMillis() default 500L;
}
