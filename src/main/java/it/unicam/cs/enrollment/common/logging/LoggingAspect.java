package it.unicam.cs.enrollment.common.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Implements {@link Loggable}: times every advised call, logs entry and exit,
 * and flags slow ones.
 *
 * <h2>The vocabulary, once</h2>
 * <ul>
 *   <li><b>Aspect</b> - this class: a cross-cutting concern in one place.</li>
 *   <li><b>Join point</b> - a place where advice could run. In Spring AOP that
 *       is always a public method call on a Spring bean, never a field access
 *       or a constructor. Full AspectJ can do more; Spring's proxy-based
 *       subset deliberately cannot.</li>
 *   <li><b>Pointcut</b> - the expression that selects join points, below.</li>
 *   <li><b>Advice</b> - the code that runs: {@code @Around} here, but
 *       {@code @Before}, {@code @After}, {@code @AfterReturning} and
 *       {@code @AfterThrowing} exist for the simpler cases.</li>
 * </ul>
 *
 * <h2>{@code @Order} - where this sits in the chain</h2>
 * Several aspects can advise the same method, and {@code @Order} decides who
 * wraps whom: LOWER numbers run further out, closer to the caller. Spring's own
 * transaction advisor defaults to {@code Ordered.LOWEST_PRECEDENCE}, so a low
 * number here means this aspect runs OUTSIDE the transaction and the duration
 * it measures includes commit time - which is usually what you want, because
 * commit is often where the time actually goes.
 */
@Aspect
@Component
@Order(10)
public class LoggingAspect {

    /**
     * The logger is named after the aspect, not after the advised class, so
     * that this diagnostic channel can be turned up or down on its own:
     * {@code logging.level.it.unicam.cs.enrollment.common.logging=DEBUG}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(LoggingAspect.class);

    /**
     * Matches a method that carries {@code @Loggable}, or any method of a class
     * that does.
     *
     * <p>Two expressions, because they ask genuinely different questions:
     * {@code @annotation(...)} looks at the METHOD, {@code within(@... *)}
     * looks at the declaring TYPE. Writing only the first is a common mistake -
     * a class-level annotation would then never fire.
     */
    @Pointcut("@annotation(it.unicam.cs.enrollment.common.logging.Loggable)"
            + " || within(@it.unicam.cs.enrollment.common.logging.Loggable *)")
    public void loggable() {
        // A pointcut is a named expression; the method body is never executed.
    }

    /**
     * The advice.
     *
     * <p>{@link ProceedingJoinPoint#proceed()} continues the chain: the next
     * aspect, or the real method if this is the last one. FORGETTING TO CALL
     * {@code proceed()} silently stops the target method from ever executing -
     * a memorable bug to debug the first time.
     */
    @Around("loggable()")
    public Object logInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Loggable config = resolveConfiguration(method, joinPoint.getTarget());

        String target = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        long startNanos = System.nanoTime();

        if (LOG.isDebugEnabled()) {
            // GUARDED LOGGING. The check avoids building the message when DEBUG
            // is off. With SLF4J's {} placeholders the formatting is already
            // deferred, so the guard mainly pays off when the arguments
            // themselves are expensive to produce - as Arrays.toString is.
            if (config != null && config.logArguments()) {
                LOG.debug("-> {}({})", target, Arrays.toString(joinPoint.getArgs()));
            } else {
                LOG.debug("-> {}", target);
            }
        }

        try {
            Object result = joinPoint.proceed();

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            long threshold = config != null ? config.slowCallThresholdMillis() : 500L;

            if (elapsedMillis >= threshold) {
                LOG.warn("<- {} completed in {}ms (slow, threshold {}ms)",
                        target, elapsedMillis, threshold);
            } else {
                LOG.debug("<- {} completed in {}ms", target, elapsedMillis);
            }
            return result;

        } catch (Throwable e) {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            // Log the type and message, NOT the full stack trace. The exception
            // is being rethrown, so whoever finally handles it will log it
            // properly. Logging a stack trace at every level on the way out is
            // how one failure becomes forty pages of duplicated log.
            LOG.debug("<- {} threw {} after {}ms: {}",
                    target, e.getClass().getSimpleName(), elapsedMillis, e.getMessage());

            // RETHROW. Advice that swallows exceptions changes the semantics of
            // the method it wraps, and inside a transaction it would also
            // suppress the rollback.
            throw e;
        }
    }

    /**
     * Finds the {@code @Loggable} that applies: the method-level annotation
     * wins over the class-level one.
     *
     * <p>The target object is consulted rather than only the method's declaring
     * class, because the method may have been declared on an interface while
     * the annotation sits on the implementation.
     */
    private Loggable resolveConfiguration(Method method, Object target) {
        Loggable methodLevel = method.getAnnotation(Loggable.class);
        if (methodLevel != null) {
            return methodLevel;
        }
        Loggable declaringType = method.getDeclaringClass().getAnnotation(Loggable.class);
        if (declaringType != null) {
            return declaringType;
        }
        return target == null ? null : target.getClass().getAnnotation(Loggable.class);
    }
}
