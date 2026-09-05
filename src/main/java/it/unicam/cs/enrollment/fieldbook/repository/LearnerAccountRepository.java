package it.unicam.cs.enrollment.fieldbook.repository;

import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.repository.AbstractJpaRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.Optional;

/**
 * Data access for {@link LearnerAccount}.
 *
 * <p>It reuses {@code AbstractJpaRepository} from the enrollment side, which is
 * the one piece of infrastructure the two bounded contexts genuinely should
 * share: {@code save}, {@code findById} and the pagination helper are about JPA,
 * not about students or learners. Sharing infrastructure is fine; sharing a
 * domain model is what causes the trouble.
 */
@ApplicationScoped
public class LearnerAccountRepository extends AbstractJpaRepository<LearnerAccount> {

    public LearnerAccountRepository() {
        super(LearnerAccount.class);
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
     * Lookup by the login name. The query sign-in runs, and the only one it
     * runs: an account is found by USERNAME or it is not found.
     *
     * <p>The handle must already be normalised to lower case by
     * {@code Username.of}, for the same reason the email lookup below says so -
     * normalising here as well would hide a caller that forgot, which is a bug
     * worth seeing rather than papering over.
     */
    public Optional<LearnerAccount> findByUsername(String normalisedUsername) {
        if (normalisedUsername == null || normalisedUsername.isEmpty()) {
            return Optional.empty();
        }
        return em().createNamedQuery("LearnerAccount.findByUsername", LearnerAccount.class)
                .setParameter("username", normalisedUsername)
                .getResultList()
                .stream()
                .findFirst();
    }

    /**
     * Whether the handle is taken.
     *
     * <p>Unlike {@link #existsByEmail}, this one IS reported to the caller:
     * registration answers "that name is taken" out loud, because a signup form
     * that silently refuses a name leaves the person with no move to make. The
     * enumeration cost is real and is the smaller of the two - a username is a
     * public-facing label, and a system that shows it anywhere has already told
     * you which ones exist.
     */
    public boolean existsByUsername(String normalisedUsername) {
        return findByUsername(normalisedUsername).isPresent();
    }

    /**
     * Lookup by the address, used by registration and by password reset.
     *
     * <p>{@code getResultList().stream().findFirst()} rather than
     * {@code getSingleResult()}: the latter throws
     * {@code NoResultException} when there is no row, so "this email is not
     * registered" - a completely normal outcome - would arrive as an exception
     * to be caught. Exceptions are for the unexpected; an empty
     * {@code Optional} is for the expected-and-empty.
     *
     * <p>The email must already be normalised to lower case by
     * {@code Email.of}. Normalising here as well would hide a caller that
     * forgot, which is a bug worth seeing rather than papering over.
     */
    public Optional<LearnerAccount> findByEmail(String normalisedEmail) {
        if (normalisedEmail == null || normalisedEmail.isEmpty()) {
            return Optional.empty();
        }
        return em().createNamedQuery("LearnerAccount.findByEmail", LearnerAccount.class)
                .setParameter("email", normalisedEmail)
                .getResultList()
                .stream()
                .findFirst();
    }

    /**
     * Whether the address is taken.
     *
     * <p>Note what this is NOT used for: the registration endpoint does not
     * report "that email is already registered" back to an anonymous caller,
     * because that turns the form into an oracle for which addresses have
     * accounts. See {@code AccountService.register}.
     */
    public boolean existsByEmail(String normalisedEmail) {
        return findByEmail(normalisedEmail).isPresent();
    }

    /**
     * Just the study days for one account, without loading the account.
     *
     * <p>This exists because the streak is needed after the transaction that
     * loaded the account has closed, and the collection is lazy. Fetching the
     * one thing the use case wants, in its own small query, beats both of the
     * usual reflexes: making the association eager (which loads it for every
     * request that does not want it) and widening the transaction to cover the
     * whole request (which holds a connection while JSON is written to a
     * socket).
     *
     * <p>Note that it selects the elements rather than the entity, so nothing
     * is placed in the persistence context and there is no entity to go stale.
     */
    public java.util.List<java.time.LocalDate> studyDaysFor(Long accountId) {
        if (accountId == null) {
            return java.util.Collections.emptyList();
        }
        return em().createQuery(
                        "SELECT d FROM LearnerAccount a JOIN a.studyDays d WHERE a.id = :id",
                        java.time.LocalDate.class)
                .setParameter("id", accountId)
                .getResultList();
    }

    /**
     * Stamp "last seen" without going through the entity.
     *
     * <h3>Why this is not {@code account.touch(now)}</h3>
     * Because that writes through a MANAGED entity, and every entity here
     * carries a {@code @Version} column. Hibernate then issues
     * {@code UPDATE ... SET version = N+1 WHERE id = ? AND version = N}, and
     * two requests that overlap - which for one browser is every single page
     * load - both read version N and one of them updates zero rows. The result
     * is an {@code OptimisticLockException} on a field nobody is competing for.
     *
     * <p>It is a good illustration of what optimistic locking is actually for.
     * It protects BUSINESS state, where two people editing the same course
     * genuinely conflict and somebody must be told. A "last seen" timestamp is
     * telemetry: the later write simply wins and no human cares. Putting it
     * under the same lock buys nothing and costs a failed request.
     *
     * <p>A bulk JPQL {@code UPDATE} goes straight to SQL. It does not touch the
     * version column, does not use the persistence context, and cannot
     * conflict. The price is the usual one for bulk operations: anything
     * already loaded in this transaction now holds a stale value, which here is
     * a timestamp nothing reads back.
     */
    public int touchLastSeen(Long accountId, java.time.Instant now) {
        if (accountId == null) {
            return 0;
        }
        return em().createQuery(
                        "UPDATE LearnerAccount a SET a.lastSeenAt = :now WHERE a.id = :id")
                .setParameter("now", now)
                .setParameter("id", accountId)
                .executeUpdate();
    }

    /**
     * Take a row lock on one account, and nothing else.
     *
     * <h3>Why this is a native query, and not {@code findByIdForUpdate}</h3>
     * The obvious call is {@code em.find(LearnerAccount.class, id,
     * PESSIMISTIC_WRITE)}, and it does not do what it looks like it does. This
     * entity has an EAGER {@code @ElementCollection} for roles, so loading it
     * is a join - and PostgreSQL rejects {@code FOR UPDATE} on a query with an
     * outer join. Hibernate notices, logs
     *
     * <pre>HHH000444: ... dialect reports that database prefers locking be done
     * in a separate select (follow-on locking)</pre>
     *
     * and splits the operation into two statements: read the row, then lock it
     * with {@code WHERE id = ? AND version = ?}.
     *
     * <p>Those two statements are not atomic, and the gap between them is
     * exactly the window the lock existed to close. Another transaction commits
     * in between, the version moves, the second statement matches nothing, and
     * you get an {@code OptimisticLockException} - from the code you added to
     * prevent one. It is a genuinely good example of an abstraction that leaks
     * quietly: nothing fails, a warning is logged, and the semantics change.
     *
     * <p>So the lock is taken as one statement that touches one table and
     * selects one column. No join, no version predicate, no follow-on
     * behaviour: it either acquires the lock or waits for it.
     *
     * <p>The cost is a native query, which is not portable SQL - {@code FOR
     * UPDATE} is standard, but this is the kind of line that has to be checked
     * against a new database. That is the honest trade: dropping out of JPQL is
     * fine when you do it deliberately and write down why.
     */
    public void lockRow(Long accountId) {
        if (accountId == null) {
            return;
        }
        em().createNativeQuery("SELECT id FROM fieldbook_accounts WHERE id = ?1 FOR UPDATE")
                .setParameter(1, accountId)
                .getResultList();
    }

    public long countAll() {
        return em().createQuery("SELECT COUNT(a) FROM LearnerAccount a", Long.class)
                .getSingleResult();
    }
}
