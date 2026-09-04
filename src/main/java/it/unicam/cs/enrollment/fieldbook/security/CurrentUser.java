package it.unicam.cs.enrollment.fieldbook.security;

import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import jakarta.enterprise.context.RequestScoped;

import java.util.Optional;

/**
 * Who is making this request. Filled in by {@link AuthenticationFilter} and
 * injected wherever the answer is needed.
 *
 * <h2>Why {@code @RequestScoped} is the whole design</h2>
 * This bean holds mutable state that belongs to one caller. Marked
 * {@code @ApplicationScoped} it would be a single shared instance and every
 * concurrent request would overwrite everyone else's identity - the worst
 * possible bug, because under light load it works perfectly and under real
 * traffic it serves one learner another learner's notes.
 *
 * <p>{@code @RequestScoped} means CDI creates one instance per HTTP request and
 * destroys it at the end. The thing being injected into your
 * {@code @ApplicationScoped} services is not this object at all: it is a PROXY,
 * which on every call looks up the instance belonging to the current request's
 * context and forwards to it. That indirection is what lets a long-lived
 * singleton hold a reference to a short-lived bean without either of them
 * knowing about threads.
 *
 * <p>The general rule this illustrates: state that varies per request must live
 * in a per-request scope. Reaching for a {@code ThreadLocal} is the same idea
 * implemented by hand, and it is how frameworks did this before CDI - with the
 * failure mode that forgetting to clear it leaks one request's identity into
 * the next, because application servers reuse threads from a pool.
 */
@RequestScoped
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
