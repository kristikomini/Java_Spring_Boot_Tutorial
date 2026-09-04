package it.unicam.cs.enrollment.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs once when the application starts, and once when it shuts down.
 *
 * <h2>{@code @Startup} - eager initialisation</h2>
 * An EJB {@code @Singleton} is normally created LAZILY, on first use. Adding
 * {@code @Startup} makes the container instantiate it during deployment and call
 * {@code @PostConstruct} immediately. That is what turns this class into an
 * application lifecycle hook.
 *
 * <p>Doing work at startup is a real design decision. Anything slow here delays
 * deployment, and anything that throws FAILS the deployment. That is often what
 * you want - a FAIL-FAST application that refuses to start when misconfigured
 * beats one that starts happily and returns errors to users. But keep it short
 * and keep it safe.
 *
 * <h2>The lifecycle annotations</h2>
 * <ul>
 *   <li>{@code @PostConstruct} - after construction and injection, before any
 *       business method. This is where initialisation goes; a constructor cannot
 *       do it, because injected fields are not populated yet.</li>
 *   <li>{@code @PreDestroy} - before the container discards the bean. Release
 *       resources here: thread pools, file handles, external connections.</li>
 * </ul>
 */
@Startup
@Singleton
public class ApplicationBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationBootstrap.class);

    /**
     * Field injection, used here rather than the constructor injection preferred
     * in the service layer.
     *
     * <p>For an EJB singleton it is the conventional style, and the testability
     * argument does not really apply: this class is a container lifecycle hook,
     * not business logic, so there is nothing here worth unit-testing in
     * isolation. Matching the surrounding idiom matters more than applying a
     * rule everywhere regardless of context.
     */
    @Inject
    private DataSeeder dataSeeder;

    @PostConstruct
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void onStartup() {
        LOG.info("=======================================================");
        LOG.info(" UNICAM Course Enrollment Service - starting up");
        LOG.info(" Jakarta EE 11 / JPA / CDI / JAX-RS");
        LOG.info("=======================================================");

        // Calling ACROSS a bean boundary, so DataSeeder's @Transactional
        // interceptor actually fires. See DataSeeder.seedIfEmpty() for why this
        // is not simply done in a @PostConstruct there.
        dataSeeder.seedIfEmpty();

        LOG.info("Application ready. Try: GET /enrollment/api/courses/open");
    }

    @PreDestroy
    public void onShutdown() {
        LOG.info("UNICAM Course Enrollment Service - shutting down cleanly");
    }
}
