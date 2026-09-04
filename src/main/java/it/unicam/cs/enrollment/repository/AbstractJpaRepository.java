package it.unicam.cs.enrollment.repository;

import it.unicam.cs.enrollment.common.Page;
import it.unicam.cs.enrollment.common.PageRequest;
import it.unicam.cs.enrollment.domain.model.BaseEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Generic CRUD operations shared by every repository.
 *
 * <h2>What the REPOSITORY PATTERN buys you</h2>
 * A repository is a collection-like facade over persistence. Business code says
 * {@code studentRepository.findByStudentNumber("123456")} and never sees an
 * {@code EntityManager}, a query string or a {@code LockModeType}. That gives
 * you three things:
 * <ul>
 *   <li><b>Testability</b> - services are tested by mocking the repository
 *       interface, with no database anywhere near the test.</li>
 *   <li><b>One place per query</b> - the same lookup is not reimplemented
 *       slightly differently in four services.</li>
 *   <li><b>A seam</b> - swapping JPA for something else touches this layer only.</li>
 * </ul>
 *
 * <p>You will also hear this called a DAO (Data Access Object). Purists
 * distinguish them - a DAO mirrors the table, a Repository speaks the domain's
 * language and deals in aggregates - but in most codebases the words are used
 * interchangeably.
 *
 * <h2>Generics</h2>
 * {@code <T extends BaseEntity>} is a BOUNDED TYPE PARAMETER: it lets this class
 * call {@code entity.getId()} and {@code isNew()}, which would be impossible
 * with a bare {@code <T>}. The {@code Class<T>} passed to the constructor is
 * there because of TYPE ERASURE - generic type arguments do not exist at
 * runtime, so a subclass must hand the class object over explicitly.
 *
 * @param <T> the entity type this repository manages
 */
public abstract class AbstractJpaRepository<T extends BaseEntity> {

    /**
     * THE CONTAINER-MANAGED ENTITY MANAGER.
     *
     * <p>{@code @PersistenceContext} does not inject a plain EntityManager - it
     * injects a proxy that resolves, on every call, to the EntityManager bound
     * to the CURRENT JTA TRANSACTION. That is why this field can live in an
     * {@code @ApplicationScoped} (single-instance, shared by all threads) bean
     * without any thread-safety problem: the shared object is a proxy, and the
     * real EntityManager behind it is per-transaction.
     *
     * <p>Never call {@code em.close()} on a container-managed EntityManager, and
     * never call {@code em.getTransaction()} - the container owns both.
     */
    @PersistenceContext(unitName = "enrollmentPU")
    protected EntityManager entityManager;

    private final Class<T> entityClass;

    protected AbstractJpaRepository(Class<T> entityClass) {
        this.entityClass = Objects.requireNonNull(entityClass);
    }

    /**
     * Test seam. An integration test creates a RESOURCE_LOCAL EntityManager
     * itself and injects it here, so the repository can be exercised against H2
     * without an application server.
     *
     * <p>{@code protected} rather than {@code public}: subclasses need it -
     * including the fieldbook repositories, which live in another package - and
     * nothing else does. It was package-private until a subclass outside this
     * package needed the same seam, which is the ordinary reason visibility
     * widens. Widen it one step, when something real requires it, and no
     * further; {@code public} here would advertise a way for application code
     * to swap the persistence context at runtime.
     */
    protected void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    protected EntityManager em() {
        return entityManager;
    }

    protected Class<T> getEntityClass() {
        return entityClass;
    }

    /**
     * Inserts or updates, choosing between the two JPA operations.
     *
     * <h3>persist vs merge - know the difference</h3>
     * <ul>
     *   <li>{@code persist(e)} makes a NEW entity managed. The same instance you
     *       passed in becomes managed and receives the generated id. Returns
     *       void.</li>
     *   <li>{@code merge(e)} copies the state of a DETACHED entity onto a
     *       managed instance and returns THAT instance. The object you passed in
     *       stays detached. Forgetting to use the return value is one of the
     *       most common JPA bugs: your changes appear to vanish.</li>
     * </ul>
     */
    public T save(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        if (entity.isNew()) {
            entityManager.persist(entity);
            return entity;
        }
        return entityManager.merge(entity);
    }

    /**
     * Look up by primary key.
     *
     * <p>Returns {@link Optional} rather than {@code null}. {@code em.find}
     * returns null for a missing row, and passing that null up through the
     * application is how {@code NullPointerException}s are born. {@code Optional}
     * makes "might not be there" part of the TYPE, so the compiler reminds the
     * caller to deal with it.
     *
     * <p>{@code find()} checks the persistence context first and only hits the
     * database on a miss.
     */
    public Optional<T> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entityManager.find(entityClass, id));
    }

    /**
     * Loads with a PESSIMISTIC WRITE lock: {@code SELECT ... FOR UPDATE}.
     *
     * <p>Other transactions attempting to lock the same row BLOCK until this
     * transaction commits. This serialises access and is the correct tool when a
     * decision depends on a value that must not change underneath you - our
     * course capacity check is exactly that case.
     *
     * <p>Use sparingly. Held locks are the raw material of deadlocks and of
     * throughput collapse under load. Rule of thumb: acquire late, release fast
     * (i.e. keep the transaction short), and always lock rows in a consistent
     * order across the codebase.
     */
    public Optional<T> findByIdForUpdate(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                entityManager.find(entityClass, id, LockModeType.PESSIMISTIC_WRITE));
    }

    /**
     * Returns a LAZY REFERENCE - a proxy carrying only the id, with no SELECT
     * issued.
     *
     * <p>Perfect for setting a foreign key: to make enrollment point at course
     * 42 you do not need course 42's data, only its id. Using
     * {@code getReference} instead of {@code find} saves an entire round trip.
     *
     * <p>The trap: if the row does not exist you get
     * {@code EntityNotFoundException} later, at first property access, from
     * somewhere confusing. Only use it when you already know the row exists.
     */
    public T getReference(Long id) {
        return entityManager.getReference(entityClass, id);
    }

    public void delete(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        // remove() only accepts a MANAGED entity. contains() tells us whether
        // this instance belongs to the current persistence context; if it is
        // detached we re-attach it with merge() first.
        T managed = entityManager.contains(entity) ? entity : entityManager.merge(entity);
        entityManager.remove(managed);
    }

    public boolean deleteById(Long id) {
        return findById(id).map(entity -> {
            entityManager.remove(entity);
            return true;
        }).orElse(false);
    }

    /**
     * A paginated {@code findAll}, built with the CRITERIA API.
     *
     * <h3>Criteria API vs JPQL</h3>
     * <ul>
     *   <li><b>JPQL</b> is a string. Concise and readable - the right choice for
     *       a fixed query. Errors show up when the query is parsed.</li>
     *   <li><b>Criteria</b> is an object graph. Verbose, but TYPE-SAFE and
     *       COMPOSABLE, which is what you need when the query shape depends on
     *       which filters the user supplied. Building JPQL by string
     *       concatenation instead is how SQL injection and unmaintainable code
     *       both happen.</li>
     * </ul>
     * Rule of thumb: JPQL (as a {@code @NamedQuery}) for fixed queries, Criteria
     * for dynamic ones. See {@code StudentRepository.search} for the dynamic case.
     */
    public Page<T> findAll(PageRequest pageRequest) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // --- the data query -------------------------------------------------
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root).orderBy(cb.asc(root.get("id")));

        TypedQuery<T> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult(pageRequest.getOffset());   // SQL OFFSET
        typedQuery.setMaxResults(pageRequest.getPageSize());  // SQL LIMIT
        List<T> content = typedQuery.getResultList();

        // --- the count query ------------------------------------------------
        // A separate query, because LIMIT/OFFSET would otherwise restrict the
        // count too. Two round trips is the standard cost of offset pagination.
        long total = count();

        return Page.of(content, pageRequest, total);
    }

    public long count() {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<T> root = query.from(entityClass);
        query.select(cb.count(root));
        return entityManager.createQuery(query).getSingleResult();
    }

    public boolean existsById(Long id) {
        return findById(id).isPresent();
    }

    /**
     * Forces pending SQL to be sent to the database NOW, without committing.
     *
     * <p>Useful when you need a generated id immediately, or when you want a
     * constraint violation to surface at a point where you can still catch it
     * meaningfully rather than at commit time.
     */
    public void flush() {
        entityManager.flush();
    }

    /**
     * Evicts an entity from the persistence context, making it DETACHED.
     * Subsequent changes to it are no longer tracked or written.
     */
    public void detach(T entity) {
        entityManager.detach(entity);
    }

    /**
     * Executes a {@link TypedQuery} that is expected to return zero or one row.
     *
     * <p>{@code getSingleResult()} throws {@code NoResultException} when nothing
     * matches - an exception for an outcome that is perfectly normal. Exceptions
     * are expensive and, more importantly, they are for exceptional situations;
     * "no student with that number" is an ordinary answer. This helper converts
     * it into an {@code Optional} once, so no caller has to write the try/catch.
     *
     * <h3>Why there is no {@code setMaxResults(2)} here</h3>
     * Capping the row count looks like an obvious safety measure, and it was in
     * an earlier version of this class. It is WRONG in the presence of a
     * COLLECTION fetch join.
     *
     * <p>A query like
     * {@code SELECT s FROM Student s LEFT JOIN FETCH s.enrollments} returns one
     * SQL row per enrollment, and Hibernate collapses them back into one Student
     * afterwards. A {@code LIMIT} would therefore truncate the COLLECTION, not
     * the results - a student with five enrollments would silently come back
     * with two. Hibernate refuses to guess: it either paginates in memory
     * (loading everything anyway) or, when
     * {@code hibernate.query.fail_on_pagination_over_collection_fetch} is
     * enabled as it is in this project, throws.
     *
     * <p>Every caller filters on a primary key or a unique column, so the result
     * set is bounded by a constraint rather than by a {@code LIMIT}. That is the
     * better guarantee anyway.
     *
     * <p>This is a good example of a "safety" measure that quietly introduced a
     * bug. Combining pagination with a collection fetch join is one of the
     * sharper edges in JPA, and worth remembering.
     */
    protected Optional<T> singleResult(TypedQuery<T> query) {
        List<T> results = query.getResultList();
        if (results.isEmpty()) {
            return Optional.empty();
        }
        if (results.size() > 1) {
            // Defensive: the query was supposed to be unique. Failing loudly
            // beats silently returning whichever row came first.
            throw new IllegalStateException(
                    "Expected at most one " + entityClass.getSimpleName()
                            + " but the query returned " + results.size());
        }
        return Optional.of(results.get(0));
    }
}
