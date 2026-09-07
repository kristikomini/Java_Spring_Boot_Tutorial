package it.unicam.cs.enrollment.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.lang.reflect.Member;

/**
 * A factory bean that makes {@code Logger} injectable anywhere.
 *
 * <h2>What a {@code @Bean} method is for</h2>
 * Spring can construct any class it is allowed to instantiate - anything it
 * finds by component scanning. But some things it cannot:
 * <ul>
 *   <li>types from a third-party library with no Spring annotations (like
 *       {@link Logger});</li>
 *   <li>objects created through a factory ({@code LoggerFactory.getLogger});</li>
 *   <li>objects whose construction depends on WHERE they are being injected -
 *       which is exactly our case.</li>
 * </ul>
 * A {@code @Bean} method inside a {@code @Configuration} class is the bridge:
 * "when someone asks for a {@code Logger}, call this method".
 *
 * <h2>{@link InjectionPoint} - the clever part</h2>
 * Spring passes metadata describing the injection point being satisfied. That
 * lets this one method give every class a logger named after THAT class, so log
 * output still reads:
 * <pre>
 *   INFO  it.unicam.cs.enrollment.service.EnrollmentService - Student enrolled
 * </pre>
 * rather than every line claiming to come from {@code LoggerConfig}.
 *
 * <p>Because the result depends on the injection point, the bean MUST be
 * {@code prototype}-scoped. A singleton - Spring's default - would create the
 * logger once and hand the same instance, named after whichever class happened
 * to ask first, to everybody. The {@code InjectionPoint} parameter is only
 * populated for prototype beans, which is Spring's way of enforcing that.
 *
 * <h2>Is this better than the classic static field?</h2>
 * The traditional idiom is:
 * <pre>
 *   private static final Logger LOG = LoggerFactory.getLogger(Foo.class);
 * </pre>
 * That is still the most common form in industry, and it works fine. The
 * injected version removes the copy-pasted line (and the copy-paste bug where
 * {@code Foo.class} is left behind in {@code Bar}), at the cost of one more
 * moving part - and it makes the logger a constructor parameter, which is why
 * the service tests in this project can assert on what was logged. Both forms
 * are used in this codebase so you recognise each: {@link LoggingAspect} keeps
 * the static field, the services take one in the constructor.
 */
@Configuration
public class LoggerConfig {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Logger logger(InjectionPoint injectionPoint) {
        return LoggerFactory.getLogger(resolveLoggerName(injectionPoint));
    }

    /**
     * Determines which class the logger should be named after.
     *
     * <p>{@code getMember()} is the field, method or constructor being injected
     * into, and its declaring class is the one we want. {@code getMethodParameter}
     * covers the constructor-injection case, which is how every service here
     * receives its logger.
     */
    private String resolveLoggerName(InjectionPoint injectionPoint) {
        if (injectionPoint.getMethodParameter() != null
                && injectionPoint.getMethodParameter().getDeclaringClass() != null) {
            return injectionPoint.getMethodParameter().getDeclaringClass().getName();
        }
        Member member = injectionPoint.getMember();
        if (member != null) {
            return member.getDeclaringClass().getName();
        }
        return LoggerConfig.class.getName();
    }
}
