package it.unicam.cs.enrollment.fieldbook.repository;

import it.unicam.cs.enrollment.fieldbook.domain.CardProgress;
import it.unicam.cs.enrollment.fieldbook.domain.ChapterProgress;
import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Collection;
import java.util.List;

/**
 * Data access for the two progress tables.
 *
 * <h2>Why one repository for two entities</h2>
 * The usual rule is one repository per AGGREGATE, not per table, and these two
 * are read, written and reset together as one unit - "this learner's progress".
 * Splitting them would produce two classes that are always injected as a pair
 * and always used in the same transaction, which is a sign the boundary is in
 * the wrong place.
 *
 * <p>It also does not extend {@code AbstractJpaRepository}, because that base
 * class is generic over a single entity type and there is no meaningful
 * {@code findById} on "progress". Inheriting from it here would mean picking
 * one of the two entities arbitrarily and getting a set of inherited methods
 * that are wrong for the other. Reusing a base class you have to fight is worse
 * than declaring the {@code EntityManager} yourself.
 */
@ApplicationScoped
public class ProgressRepository {

    @PersistenceContext(unitName = "enrollmentPU")
    private EntityManager em;

    /** Test seam, mirroring the other repositories in this package. */
    void useEntityManager(EntityManager em) {
        this.em = em;
    }

    // ---------------------------------------------------------------- cards

    public List<CardProgress> cardsFor(LearnerAccount account) {
        return em.createNamedQuery("CardProgress.findByAccount", CardProgress.class)
                .setParameter("account", account)
                .getResultList();
    }

    /**
     * Load exactly the cards a sync mentions.
     *
     * <p>{@code IN :keys} with an empty collection is a portability trap:
     * {@code WHERE x IN ()} is a syntax error in several databases and
     * providers differ on whether they even get that far. Checking for empty
     * here costs one branch and removes a class of bug that only shows up on
     * the one request where nothing changed.
     */
    public List<CardProgress> cardsFor(LearnerAccount account, Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return em.createNamedQuery("CardProgress.findByAccountAndKeys", CardProgress.class)
                .setParameter("account", account)
                .setParameter("keys", keys)
                .getResultList();
    }

    public void add(CardProgress card) {
        em.persist(card);
    }

    // ------------------------------------------------------------- chapters

    public List<ChapterProgress> chaptersFor(LearnerAccount account) {
        return em.createNamedQuery("ChapterProgress.findByAccount", ChapterProgress.class)
                .setParameter("account", account)
                .getResultList();
    }

    public void add(ChapterProgress chapter) {
        em.persist(chapter);
    }

    // ---------------------------------------------------------------- reset

    /**
     * Throw away everything. Offered because a learner who wants to start the
     * course again should not have to delete their account to do it, and
     * because being able to get your data out and wipe it is the least a tool
     * that stores your study history owes you.
     */
    public int resetFor(LearnerAccount account) {
        int cards = em.createNamedQuery("CardProgress.deleteForAccount")
                .setParameter("account", account)
                .executeUpdate();
        int chapters = em.createNamedQuery("ChapterProgress.deleteForAccount")
                .setParameter("account", account)
                .executeUpdate();
        // Bulk DELETE bypasses the persistence context, so anything loaded
        // earlier in this transaction is now a reference to a row that no
        // longer exists. Clearing makes that explicit instead of leaving a
        // stale object graph behind for the next line of code to trip over.
        em.clear();
        return cards + chapters;
    }
}
