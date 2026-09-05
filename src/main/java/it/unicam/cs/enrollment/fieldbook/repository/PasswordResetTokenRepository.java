package it.unicam.cs.enrollment.fieldbook.repository;

import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.domain.PasswordResetToken;
import it.unicam.cs.enrollment.repository.AbstractJpaRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Optional;

/**
 * Data access for {@link PasswordResetToken}.
 *
 * <p>Small, and every method on it exists because one step of the reset flow
 * needs exactly that query - which is the shape a repository should have. A
 * repository that grows a {@code findAll} nobody calls is a repository that
 * will eventually be used to load the whole table.
 */
@ApplicationScoped
public class PasswordResetTokenRepository extends AbstractJpaRepository<PasswordResetToken> {

    public PasswordResetTokenRepository() {
        super(PasswordResetToken.class);
    }

    /**
     * Test seam, package-private so only the integration test in this package
     * can reach it. See {@code LearnerAccountRepository.useEntityManager} for
     * why a two-line method is the way to re-export a protected setter.
     */
    void useEntityManager(jakarta.persistence.EntityManager entityManager) {
        setEntityManager(entityManager);
    }

    /**
     * Look one up by the fingerprint of the token in the link.
     *
     * <p>{@code getResultList().stream().findFirst()} rather than
     * {@code getSingleResult()}, for the reason spelled out in
     * {@code LearnerAccountRepository.findByEmail}: "that link is not one I
     * issued" is an ordinary outcome, and ordinary outcomes should not arrive
     * as exceptions.
     */
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isEmpty()) {
            return Optional.empty();
        }
        return em().createNamedQuery("PasswordResetToken.findByTokenHash", PasswordResetToken.class)
                .setParameter("tokenHash", tokenHash)
                .getResultList()
                .stream()
                .findFirst();
    }

    /**
     * Kill every outstanding request for one account.
     *
     * <p>Called when a new one is issued, and again when a reset succeeds.
     * Without the first call, asking for three reset emails leaves three live
     * links and the oldest one - the one most likely to have been forwarded,
     * quoted or archived - stays valid for an hour. "The newest request wins"
     * is what a person means when they click the button again.
     *
     * <p>Stamped rather than deleted, so the audit trail survives; see the
     * entity's javadoc. {@code used_at} is set to now for rows that were still
     * alive, which is also what makes them fail {@code isUsable} afterwards.
     */
    public int invalidateAllFor(LearnerAccount account, Instant now) {
        if (account == null) {
            return 0;
        }
        return em().createQuery(
                        "UPDATE PasswordResetToken t SET t.usedAt = :now "
                                + "WHERE t.account = :account AND t.usedAt IS NULL")
                .setParameter("now", now)
                .setParameter("account", account)
                .executeUpdate();
    }

    /**
     * How many requests this account has made since {@code since}.
     *
     * <p>The mail-flood limiter. {@code LoginThrottle} counts FAILURES, and a
     * reset request never fails - so without this, one form submitted in a loop
     * is a way of using this application to post a thousand emails at somebody
     * who never asked for any of them. The endpoint being anonymous is what
     * makes that easy; a counter over rows we already write is what makes it
     * cheap to stop.
     */
    public long countIssuedSince(LearnerAccount account, Instant since) {
        if (account == null) {
            return 0;
        }
        return em().createQuery(
                        "SELECT COUNT(t) FROM PasswordResetToken t "
                                + "WHERE t.account = :account AND t.createdAt >= :since",
                        Long.class)
                .setParameter("account", account)
                .setParameter("since", since)
                .getSingleResult();
    }

    /** Everything for one account, when the account itself is being deleted. */
    public int deleteAllFor(LearnerAccount account) {
        if (account == null) {
            return 0;
        }
        return em().createQuery("DELETE FROM PasswordResetToken t WHERE t.account = :account")
                .setParameter("account", account)
                .executeUpdate();
    }

    /**
     * Housekeeping for the nightly job: drop spent and expired rows once they
     * are older than the audit window.
     *
     * <p>Same bulk-delete caveat as {@code AuthSessionRepository.deleteExpired}
     * - it does not update the persistence context, so it belongs in a
     * transaction with nothing else in it.
     */
    public int deleteOlderThan(Instant cutoff) {
        return em().createQuery(
                        "DELETE FROM PasswordResetToken t "
                                + "WHERE t.expiresAt < :cutoff OR t.usedAt < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }
}
