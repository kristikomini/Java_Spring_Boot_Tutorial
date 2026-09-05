package it.unicam.cs.enrollment.spring.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * ============================================================================
 * THE SAME COURSE, MODELLED FOR A DOCUMENT STORE
 * ============================================================================
 * Read this next to domain/Course.java. Same subject, opposite design, and the
 * differences are the whole of what NoSQL means for someone who thinks
 * relationally.
 *
 * <p>1. EMBED INSTEAD OF REFERENCE. The relational Course has a professor_id
 * pointing at another table; this has the professor INSIDE it, and the whole
 * roster of enrolled students inside it too. One read returns everything the
 * page needs. There is no join because there is nothing to join to.
 *
 * <p>THE RULE FOR CHOOSING, and it is the most-asked NoSQL design question:
 * EMBED WHAT YOU READ TOGETHER AND WRITE TOGETHER; REFERENCE WHAT IS SHARED,
 * LARGE, OR CHANGES INDEPENDENTLY.
 *
 * <p>The professor is embedded even though a professor is shared between
 * courses. That is a deliberate, and slightly uncomfortable, denormalisation:
 * rename a professor and every course document holding a copy is stale until
 * something rewrites it. In a relational schema that update is one row and
 * correct everywhere by construction. Here it is a job.
 *
 * <p>The trade is acceptable ONLY because this is a read model that a batch
 * rebuilds. If this were the system of record for professors it would be the
 * wrong design, and the answer would be a reference plus a second query.
 *
 * <p>2. NO FOREIGN KEYS, NO UNIQUE CONSTRAINT ACROSS DOCUMENTS. A unique index
 * inside one collection exists; a guarantee that an enrollment points at a real
 * course does not. Nothing stops you writing a document that references a
 * course somebody deleted. The database will not catch it, so your code must -
 * and that is the concrete version of what chapter 33 means when it says this
 * application "would be materially harder to make correct in a document store".
 *
 * <p>3. THE SCHEMA IS FLEXIBLE, WHICH IS NOT THE SAME AS ABSENT. Mongo will
 * happily store two documents in one collection with different fields. Genuinely
 * useful during rapid change, and genuinely dangerous afterwards: the shape then
 * lives only in the code that reads it, and old documents keep whatever shape
 * they had when they were written. Every read has to cope with every shape the
 * collection has ever contained. Schema validation exists in MongoDB and is
 * worth turning on for exactly this reason.
 *
 * <p>4. THE 16 MB DOCUMENT LIMIT is a hard boundary and a design constraint, not
 * a footnote. Embedding an unbounded array - every enrollment for all time -
 * eventually reaches it, and the failure is writes starting to be rejected. The
 * rule is never to embed an array that grows without bound. This one is bounded
 * by course capacity, which is validated at 1000, so it is safe by construction
 * - and knowing WHY it is safe is the point.
 *
 * <p>WHY {@code @Document} AND NOT {@code @Entity}. Different annotation,
 * different Spring Data module, same repository abstraction. Spring Data JPA and
 * Spring Data MongoDB both give you derived queries over a repository interface,
 * which is one of the genuinely good things about Spring Data - most of what you
 * know transfers. What does NOT transfer is everything the relational database
 * was giving you for free.
 */
@Document(collection = "course_read_model")
@CompoundIndex(name = "idx_year_semester", def = "{'academicYear': 1, 'semester': 1}")
public class CourseDocument {

    /**
     * The MongoDB _id, set from the PostgreSQL course id so the two stores can
     * be reconciled - which you will need on the day the read model is wrong and
     * somebody has to work out why.
     *
     * <p>{@code org.springframework.data.annotation.Id}, NOT
     * {@code jakarta.persistence.Id}. Two identically named annotations from
     * different specifications, and importing the wrong one gives you a document
     * with no id and a confusing afternoon.
     */
    @Id
    private String id;

    @Indexed
    private String code;

    private String title;
    private int credits;
    private int capacity;
    private int academicYear;
    private String semester;

    /** EMBEDDED, not referenced. See the note above about the trade. */
    private ProfessorInfo professor;

    /**
     * The roster, embedded.
     *
     * <p>In PostgreSQL this is a join to an indexed table. Here it is an array
     * inside the document, so the page renders from ONE read with no join and no
     * N+1 - which is the thing document stores are genuinely good at.
     *
     * <p>Bounded by course capacity, which is the only reason embedding is safe.
     */
    private List<EnrolledStudent> students;

    private long occupiedSeats;
    private long availableSeats;

    /**
     * A {@code double}, WHERE THE RELATIONAL TABLE USES {@code NUMERIC(5,2)}.
     *
     * <p>That difference is deliberate and it took two failed attempts to arrive
     * at, both of which are worth recording because they are the most common
     * BigDecimal-on-MongoDB mistakes.
     *
     * <p>ATTEMPT 1 - a plain {@code BigDecimal}. Spring Data MongoDB stores a
     * BigDecimal AS A STRING by default, to preserve scale exactly. The
     * consequence is silent and total: {@code { fillRate: { $lt: 25.0 } }}
     * returns ZERO documents, always, because MongoDB is comparing a number
     * against a string and they are different BSON types. No error, no warning,
     * just an empty result. Sorting is worse still - as strings, "100.00" sorts
     * before "20.00".
     *
     * <p>ATTEMPT 2 - {@code @Field(targetType = DECIMAL128)}. This stores it
     * correctly as a 128-bit decimal, and then the QUERY fails instead: a
     * {@code ?0} placeholder inside a JSON filter binds as a string, and Spring
     * cannot convert it to Decimal128. A loud failure rather than a silent one,
     * which is an improvement, but still a failure.
     *
     * <p>THE RESOLUTION is to ask what the value actually is. This is a
     * PERCENTAGE IN A DERIVED READ MODEL - not money, not summed, not the system
     * of record, and recomputed from PostgreSQL every rebuild. A double is
     * exactly right for it, queries and sorts natively, and loses nothing that
     * matters. BigDecimal earns its place where precision is a correctness
     * requirement: money, and anything that gets added up. The relational
     * {@code course_statistics.pass_rate} stays NUMERIC because that is the
     * stored figure a person reads.
     *
     * <p>THE WIDER LESSON is worth more than either fix. A schemaless store
     * accepts whatever you write, so a type mismatch does not fail - it produces
     * wrong RESULTS. In PostgreSQL, NUMERIC versus TEXT is caught by the schema
     * before a single row is written. That is the trade being made when the
     * schema goes away, and it is why "flexible" and "no constraints" are the
     * same sentence read twice.
     */
    private double fillRate;

    /** As of when. Same reasoning as {@code course_statistics.computed_at}. */
    private Instant computedAt;

    public CourseDocument() {
    }

    /** Embedded, therefore denormalised. Renaming a professor makes copies stale. */
    public record ProfessorInfo(String staffNumber, String fullName,
                                String title, String department) {
    }

    /**
     * Embedded, and DELIBERATELY NOT the whole Student.
     *
     * <p>Only what the roster view renders. Copying every field "in case" is how
     * a document grows towards the size limit, and every extra field is one more
     * thing that goes stale.
     */
    public record EnrolledStudent(String studentNumber, String fullName,
                                  String status, Integer grade) {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getCredits() {
        return credits;
    }

    public void setCredits(int credits) {
        this.credits = credits;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getAcademicYear() {
        return academicYear;
    }

    public void setAcademicYear(int academicYear) {
        this.academicYear = academicYear;
    }

    public String getSemester() {
        return semester;
    }

    public void setSemester(String semester) {
        this.semester = semester;
    }

    public ProfessorInfo getProfessor() {
        return professor;
    }

    public void setProfessor(ProfessorInfo professor) {
        this.professor = professor;
    }

    public List<EnrolledStudent> getStudents() {
        return students;
    }

    public void setStudents(List<EnrolledStudent> students) {
        this.students = students;
    }

    public long getOccupiedSeats() {
        return occupiedSeats;
    }

    public void setOccupiedSeats(long occupiedSeats) {
        this.occupiedSeats = occupiedSeats;
    }

    public long getAvailableSeats() {
        return availableSeats;
    }

    public void setAvailableSeats(long availableSeats) {
        this.availableSeats = availableSeats;
    }

    public double getFillRate() {
        return fillRate;
    }

    public void setFillRate(double fillRate) {
        this.fillRate = fillRate;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }
}
