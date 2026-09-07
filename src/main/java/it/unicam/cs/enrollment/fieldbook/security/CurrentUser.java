package it.unicam.cs.enrollment.fieldbook.security;

import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Optional;

/**
 * Who is making this request. Filled in by {@link AuthenticationInterceptor}
 * and injected wherever the answer is needed.
 *
 * <h2>Why {@code @RequestScope} is the whole design</h2>
 * This bean holds mutable state that belongs to one caller. As a singleton -
 * Spring's default, and what you get by writing {@code @Component} alone -
 * there would be one shared instance and every concurrent request would
 * overwrite everyone else's identity. That is the worst possible bug, because
 * under light load it works perfectly and under real traffic it serves one
 * learner another learner's notes.
 *
 * <p>{@code @RequestScope} means Spring creates one instance per HTTP request
 * and discards it at the end. What gets injected into your singleton services
 * is not this object at all: it is a PROXY, which on every call looks up the
 * instance belonging to the current request and forwards to it. That
 * indirection is what lets a long-lived singleton hold a reference to a
 * short-lived bean without either of them knowing about threads.
 *
 * <p>The general rule this illustrates: state that varies per request must live
 * in a per-request scope. Reaching for a {@code ThreadLocal} is the same idea
 * implemented by hand - and is, in fact, exactly how Spring implements it, via
 * {@code RequestContextHolder}. Doing it yourself has one nasty failure mode
 * the framework handles for you: forgetting to clear the thread-local leaks one
 * request's identity into the next, because servers reuse threads from a pool.
 */
@Component
@RequestScope
public class CurrentUser {

    private LearnerAccount account;
    private AuthSession session;

    /**
     * {@code Optional} rather than a nullable getter, so that a caller which
     * forgets the anonymous case does not compile. Endpoints behind
     * {@link Authenticated} can use {@link #require()} instead.
     */
    public Optional<LearnerAccount> account() {
        return Optional.ofNullable(account);
    }

    public Optional<AuthSession> session() {
        return Optional.ofNullable(session);
    }

    public boolean isAuthenticated() {
        return account != null;
    }

    /**
     * The account, or an exception. Safe on any endpoint annotated
     * {@link Authenticated}, because the filter has already rejected the
     * request otherwise. The exception is therefore a programming error - an
     * endpoint that forgot the annotation - and is deliberately not a 401: it
     * should be a loud 500 in a test run, not a quiet auth failure in
     * production.
     */
    public LearnerAccount require() {
        if (account == null) {
            throw new IllegalStateException(
                    "No authenticated account on this request. Is the endpoint missing @Authenticated?");
        }
        return account;
    }

    public boolean hasRole(String role) {
        return account != null && account.hasRole(role);
    }

    /** Package-private: only the filter in this package gets to say who you are. */
    void set(LearnerAccount account, AuthSession session) {
        this.account = account;
        this.session = session;
    }
}
