package it.unicam.cs.enrollment.spring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.Instant;

/**
 * The shared identity, optimistic-lock and audit columns - identical to the
 * Jakarta EE version, because they describe the same physical rows.
 *
 * <p>THE SEQUENCE MUST MATCH. {@code app_id_seq} with
 * {@code allocationSize = 50} is not a style choice, it is a hard constraint:
 * both applications draw ids from the same PostgreSQL sequence, and
 * allocationSize is the promise Hibernate makes about how it will use the
 * numbers it is given. It asks the sequence once and then hands out the next 50
 * values from memory - the write-path optimisation fieldbook chapter 25
 * describes, one round trip instead of fifty. If the two applications disagreed
 * about the size, one would hand out ids the other believes it already owns, and
 * you would get primary key collisions that appear only under concurrent load
 * from both.
 *
 * <p>THE equals/hashCode CONTRACT is the same trap in both frameworks and is
 * worth re-reading (fieldbook chapter 08). {@code hashCode()} returns the CLASS
 * hash - a constant - not the hash of the id. That looks wrong and is
 * deliberate: a transient entity has a null id, so hashing on the id would
 * change the hash of the object the moment it is persisted, and any HashSet it
 * had already been added to would lose it. A constant hash is legal (it costs
 * only bucket collisions) and it is stable across the persist. Spring changes
 * nothing about this; it is a Java contract, not a framework one.
 */
@MappedSuperclass
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_id_generator")
    @SequenceGenerator(name = "app_id_generator", sequenceName = "app_id_seq", allocationSize = 50)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /**
     * The optimistic lock. Hibernate appends {@code WHERE version = ?} to every
     * UPDATE and throws if no row matched, which is how two users editing the
     * same row are detected without holding a database lock between requests.
     *
     * <p>Spring surfaces the resulting failure as
     * {@code ObjectOptimisticLockingFailureException} rather than the JPA
     * {@code OptimisticLockException} - see RestExceptionHandler, which has to
     * account for both.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * JPA lifecycle callbacks, not Spring ones. They are part of the
     * specification and fire identically under both containers.
     *
     * <p>Spring Data does offer an alternative - {@code @CreatedDate} and
     * {@code @LastModifiedDate} with {@code @EnableJpaAuditing} - and that is
     * what you will meet in most Spring codebases. It is deliberately not used
     * here, so this class stays diffable against its counterpart. Worth knowing
     * both exist, and worth knowing the Spring one also gives you the "who" as
     * well as the "when", once there is a security context to ask.
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isNew() {
        return id == null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseEntity)) {
            return false;
        }
        BaseEntity that = (BaseEntity) other;
        return this.getId() != null && this.getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id=" + id + ", version=" + version + "}";
    }
}
