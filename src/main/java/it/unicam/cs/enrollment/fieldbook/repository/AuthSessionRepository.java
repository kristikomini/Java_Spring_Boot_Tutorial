package it.unicam.cs.enrollment.fieldbook.repository;

import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.repository.AbstractJpaRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.Optional;

/**
 * Data access for {@link AuthSession}.
 */
@ApplicationScoped
public class AuthSessionRepository extends AbstractJpaRepository<AuthSession> {

    public AuthSessionRepository() {
        super(AuthSession.class);
    }

    /**
     * Test seam, package-private so that only the integration test in this
     * package can reach it.
     *
     * <p>The inherited setter is {@code protected}, which lets a SUBCLASS call
     * it but not an unrelated test class - protected access is granted to
     * subclasses acting on themselves, not to anybody holding a reference. This
     * two-line method is how a subclass re-exports it to its own package
     * without widening it for everyone. Fiddly, and a fair illustration of why
     * Java visibility is worth actually knowing rather than guessing at.
     */
    void useEntityManager(EntityManager entityManager) {
        setEntityManager(entityManager);
    }

    /**
     * The hot path: one lookup on every authenticated request.
     *
     * <p>The named query is a {@code JOIN FETCH} of the account, because the
     * caller always needs it. Without that the lazy {@code @ManyToOne} would
     * fire a second SELECT per request - the N+1 pattern in its smallest form,
     * where N happens to be 1 and the cost is simply doubled for no reason.
     *
     * <p>{@code token_hash} carries a unique index, so this is a single index
     * probe regardless of how many sessions exist.
     */
    public Optional<AuthSession> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isEmpty()) {
            return Optional.empty();
        }
        return em().createNamedQuery("AuthSession.findByTokenHash", AuthSession.class)
                .setParameter("tokenHash", tokenHash)
                .getResultList()
                .stream()
                .findFirst();
    }

    /**
     * Log out everywhere. Used by "sign out of all devices" and after a
     * password change - a password change that leaves old sessions alive has
     * not actually locked anybody out.
     */
    public int deleteAllForAccount(LearnerAccount account) {
        return em().createNamedQuery("AuthSession.deleteForAccount")
                .setParameter("account", account)
                .executeUpdate();
    }

    /**
     * Push a session's expiry out, without a version conflict.
     *
     * <p>Same reasoning as {@code LearnerAccountRepository.touchLastSeen}: a
     * sliding expiry is not business state that two callers can meaningfully
     * disagree about, so it has no business failing a request because two tabs
     * refreshed at once.
     */
    public int extendTo(Long sessionId, Instant expiresAt) {
        if (sessionId == null) {
            return 0;
        }
        return em().createQuery(
                        "UPDATE AuthSession s SET s.expiresAt = :expiresAt WHERE s.id = :id")
                .setParameter("expiresAt", expiresAt)
                .setParameter("id", sessionId)
                .executeUpdate();
    }

    /**
     * Housekeeping, called by the scheduled job.
     *
     * <p>A bulk {@code DELETE} in JPQL goes straight to SQL and does NOT update
     * the persistence context: entities already loaded in this transaction stay
     * loaded and will happily be used after the rows behind them are gone. That
     * is a genuine trap, and the reason bulk operations belong at the start of
     * a transaction or in one of their own. Here there is nothing else in the
     * transaction, which is the easy way to be right.
     */
    public int deleteExpired(Instant now) {
        return em().createNamedQuery("AuthSession.deleteExpired")
                .setParameter("now", now)
                .executeUpdate();
    }
}
