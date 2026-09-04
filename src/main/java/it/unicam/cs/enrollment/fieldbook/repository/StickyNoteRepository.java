package it.unicam.cs.enrollment.fieldbook.repository;

import it.unicam.cs.enrollment.fieldbook.domain.LearnerAccount;
import it.unicam.cs.enrollment.fieldbook.domain.StickyNote;
import it.unicam.cs.enrollment.repository.AbstractJpaRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link StickyNote}.
 */
@ApplicationScoped
public class StickyNoteRepository extends AbstractJpaRepository<StickyNote> {

    public StickyNoteRepository() {
        super(StickyNote.class);
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

    public List<StickyNote> findAllFor(LearnerAccount account) {
        return em().createNamedQuery("StickyNote.findByAccount", StickyNote.class)
                .setParameter("account", account)
                .getResultList();
    }

    public List<StickyNote> findFor(LearnerAccount account, String chapterId) {
        return em().createNamedQuery("StickyNote.findByAccountAndChapter", StickyNote.class)
                .setParameter("account", account)
                .setParameter("chapterId", chapterId)
                .getResultList();
    }

    /**
     * Load a note, but only if it belongs to this account.
     *
     * <p>This signature is the whole point. The obvious alternative is
     * {@code findById(noteId)} followed by an ownership check in the service,
     * and the obvious alternative is how INSECURE DIRECT OBJECT REFERENCE bugs
     * get written: somebody adds a second code path, forgets the check, and now
     * {@code DELETE /notes/41} deletes whoever's note 41 is. Putting the owner
     * in the query means there is no version of this method that can return
     * somebody else's row.
     *
     * <p>It is the most common serious finding in a first API security review,
     * and it is prevented by an interface decision rather than by remembering.
     */
    public Optional<StickyNote> findOwned(LearnerAccount account, Long noteId) {
        if (noteId == null) {
            return Optional.empty();
        }
        return em().createQuery(
                        "SELECT n FROM StickyNote n WHERE n.id = :id AND n.account = :account",
                        StickyNote.class)
                .setParameter("id", noteId)
                .setParameter("account", account)
                .getResultList()
                .stream()
                .findFirst();
    }

    /**
     * The largest sort index in use, so a new note lands at the end of the
     * board rather than in the middle of it.
     *
     * <p>{@code COALESCE} because {@code MAX} over no rows is {@code NULL}, and
     * an unboxed {@code double} return would then throw a
     * {@code NullPointerException} on the very first note - a bug that is
     * invisible until the empty case happens, which in testing it never does.
     */
    public double highestSortIndex(LearnerAccount account) {
        return em().createQuery(
                        "SELECT COALESCE(MAX(n.sortIndex), 0) FROM StickyNote n WHERE n.account = :account",
                        Double.class)
                .setParameter("account", account)
                .getSingleResult();
    }

    public int deleteAllFor(LearnerAccount account) {
        return em().createNamedQuery("StickyNote.deleteForAccount")
                .setParameter("account", account)
                .executeUpdate();
    }
}
