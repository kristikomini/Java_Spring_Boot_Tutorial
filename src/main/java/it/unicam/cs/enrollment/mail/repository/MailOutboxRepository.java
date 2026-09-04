package it.unicam.cs.enrollment.mail.repository;

import it.unicam.cs.enrollment.common.Page;
import it.unicam.cs.enrollment.common.PageRequest;
import it.unicam.cs.enrollment.mail.domain.MailStatus;
import it.unicam.cs.enrollment.mail.domain.OutboxMessage;
import it.unicam.cs.enrollment.repository.AbstractJpaRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Queries over the mail outbox.
 *
 * <p>Everything the dispatcher needs is here, and nothing that decides anything:
 * a repository answers questions about stored state, it does not choose what to
 * do about the answers. Keeping that line means the retry rules live in one
 * readable place ({@code MailDispatcher} and {@code OutboxMessage}) instead of
 * being spread across a query and a service.
 */
@ApplicationScoped
public class MailOutboxRepository extends AbstractJpaRepository<OutboxMessage> {

    public MailOutboxRepository() {
        super(OutboxMessage.class);
    }

    /**
     * Test seam, package-private so only the integration test beside it can
     * reach it.
     *
     * <p>The inherited setter is {@code protected}, which lets a SUBCLASS call
     * it on itself but not an unrelated class holding a reference - so this
     * two-line method is how a subclass re-exports the seam to its own package
     * without widening it for everyone. The fieldbook repositories do the same
     * thing for the same reason; it is worth understanding once, because
     * protected-across-packages is the corner of Java visibility that people
     * most often guess at.
     */
    void useEntityManager(jakarta.persistence.EntityManager entityManager) {
        setEntityManager(entityManager);
    }

    /**
     * The dispatcher's query: messages that are waiting and whose time has come,
     * oldest due first.
     *
     * <h3>Why the ids and not the entities</h3>
     * The caller loads each row again, one per transaction, immediately after.
     * Returning full entities here would mean loading them inside a transaction
     * that ends before they are used - so they would be detached by the time
     * anything touched them, and the second load would happen anyway. Returning
     * ids says exactly that: this query is a work LIST, not the work.
     *
     * <h3>What this deliberately does not do</h3>
     * There is no {@code FOR UPDATE SKIP LOCKED}. Two dispatchers running this
     * query at the same moment get the same ids, and both try to claim them -
     * the loser of the claim finds the row no longer PENDING and moves on, so
     * nothing is sent twice. That is correct, but it is optimistic: it wastes a
     * little work under contention, and it relies on the claim being a
     * conditional update rather than a blind one.
     *
     * <p>The reason it is left this way is that this application runs one
     * dispatcher, on one node, serialised by {@code @Singleton}. A clustered
     * deployment needs a real answer - {@code SKIP LOCKED} (PostgreSQL and
     * modern MySQL support it; H2, which the tests use, does not), or a message
     * broker whose whole job is handing each item to exactly one consumer.
     * Choosing the simple version knowingly is engineering; choosing it because
     * the problem never occurred to you is the thing to avoid.
     */
    public List<Long> findDueIds(Instant now, int limit) {
        TypedQuery<Long> query = em().createQuery(
                "SELECT m.id FROM OutboxMessage m "
                        + "WHERE m.status = :status AND m.nextAttemptAt <= :now "
                        + "ORDER BY m.nextAttemptAt ASC, m.id ASC",
                Long.class);
        query.setParameter("status", MailStatus.PENDING);
        query.setParameter("now", now);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * Messages claimed for sending so long ago that whoever claimed them is
     * gone: a redeploy in the middle of a batch, a killed container, an
     * OutOfMemoryError.
     *
     * <p>Without this sweep those rows sit in SENDING forever, and the student
     * never gets the mail - the failure mode of every claim-based queue that
     * forgets that workers die between the claim and the outcome.
     */
    public List<Long> findStuckIds(Instant claimedBefore, int limit) {
        TypedQuery<Long> query = em().createQuery(
                "SELECT m.id FROM OutboxMessage m "
                        + "WHERE m.status = :status AND m.claimedAt < :claimedBefore "
                        + "ORDER BY m.claimedAt ASC",
                Long.class);
        query.setParameter("status", MailStatus.SENDING);
        query.setParameter("claimedBefore", claimedBefore);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * Used by {@code MailService} to answer "have we already queued this?".
     *
     * <p>The check is a convenience, not the guarantee: two concurrent callers
     * can both find nothing and both insert. What actually prevents the
     * duplicate is the UNIQUE constraint on the column, which is enforced by the
     * database at commit time and knows about every writer. A read-then-write
     * check in application code is always a race; its value is producing a
     * friendly answer in the common case instead of a constraint violation.
     */
    public Optional<OutboxMessage> findByDedupeKey(String dedupeKey) {
        if (dedupeKey == null) {
            return Optional.empty();
        }
        TypedQuery<OutboxMessage> query = em().createQuery(
                "SELECT m FROM OutboxMessage m WHERE m.dedupeKey = :key", OutboxMessage.class);
        query.setParameter("key", dedupeKey);
        return singleResult(query);
    }

    /**
     * The mailbox view: newest first, optionally filtered by status.
     *
     * <p>Newest first because the question being asked is almost always "what
     * just happened", and paging back through six months of delivered mail to
     * reach this morning's failure is a UI nobody uses twice.
     */
    public Page<OutboxMessage> findByStatus(MailStatus status, PageRequest pageRequest) {
        String filter = status == null ? "" : " WHERE m.status = :status";

        TypedQuery<OutboxMessage> dataQuery = em().createQuery(
                "SELECT m FROM OutboxMessage m" + filter + " ORDER BY m.id DESC",
                OutboxMessage.class);
        TypedQuery<Long> countQuery = em().createQuery(
                "SELECT COUNT(m) FROM OutboxMessage m" + filter, Long.class);

        if (status != null) {
            dataQuery.setParameter("status", status);
            countQuery.setParameter("status", status);
        }

        dataQuery.setFirstResult(pageRequest.getOffset());
        dataQuery.setMaxResults(pageRequest.getPageSize());

        return Page.of(dataQuery.getResultList(), pageRequest, countQuery.getSingleResult());
    }

    /**
     * How many messages sit in each state - the two or three numbers a health
     * check is actually made of.
     *
     * <p>One grouped query rather than five counts. The difference is invisible
     * at this size and is the habit that matters at any other size: a dashboard
     * that issues one query per tile is the N+1 problem wearing a different hat.
     */
    public Map<MailStatus, Long> countByStatus() {
        List<Object[]> rows = em().createQuery(
                        "SELECT m.status, COUNT(m) FROM OutboxMessage m GROUP BY m.status",
                        Object[].class)
                .getResultList();

        Map<MailStatus, Long> counts = new EnumMap<>(MailStatus.class);
        // Every state present, including the empty ones: a health endpoint that
        // omits "dead" when there are none forces the reader to know the field
        // could have been there. Zero is information.
        for (MailStatus status : MailStatus.values()) {
            counts.put(status, 0L);
        }
        for (Object[] row : rows) {
            counts.put((MailStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** The oldest messages still waiting - used to spot a queue that has stalled. */
    public List<OutboxMessage> findOldestPending(int limit) {
        TypedQuery<OutboxMessage> query = em().createQuery(
                "SELECT m FROM OutboxMessage m WHERE m.status = :status ORDER BY m.id ASC",
                OutboxMessage.class);
        query.setParameter("status", MailStatus.PENDING);
        query.setMaxResults(limit);
        return new ArrayList<>(query.getResultList());
    }

    /**
     * Deletes delivered messages older than the cutoff, and returns how many.
     *
     * <h3>A BULK DELETE, with the caveat that goes with it</h3>
     * This is one SQL statement, not "load ten thousand entities and remove
     * them one by one". It is orders of magnitude faster and it BYPASSES THE
     * PERSISTENCE CONTEXT: no entity callbacks run, no cascades happen, and any
     * copy of a deleted row already loaded in the current context is now stale.
     * That is safe here because the purge runs in its own transaction and
     * touches nothing else - state the condition, do not assume it.
     *
     * <p>Only SENT rows are eligible. DEAD messages are kept indefinitely on
     * purpose: they are the record of mail somebody was promised and never got,
     * and a retention policy that quietly deletes the evidence of failure is a
     * retention policy that guarantees nobody ever fixes the cause.
     */
    public int purgeSentBefore(Instant cutoff) {
        return em().createQuery(
                        "DELETE FROM OutboxMessage m WHERE m.status = :status AND m.sentAt < :cutoff")
                .setParameter("status", MailStatus.SENT)
                .setParameter("cutoff", cutoff)
                .executeUpdate();
    }
}
