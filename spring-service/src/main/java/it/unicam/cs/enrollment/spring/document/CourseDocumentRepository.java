package it.unicam.cs.enrollment.spring.document;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * ============================================================================
 * THE SAME REPOSITORY ABSTRACTION, A DIFFERENT DATABASE
 * ============================================================================
 * Compare with CourseRepository. That extends {@code JpaRepository}, this
 * extends {@code MongoRepository}, and the derived-query mechanism is
 * IDENTICAL: {@code findByCodeAndAcademicYear} is parsed from the method name
 * the same way and validated against the document metamodel at startup.
 *
 * <p>That portability is genuinely the best thing about Spring Data, and it is
 * the honest answer to "how hard is it to learn MongoDB coming from JPA": the
 * repository layer is nearly free. What is NOT free is the modelling - see
 * CourseDocument - and everything the relational database used to guarantee.
 *
 * <p>WHAT CHANGES, and these are the three things to be able to name:
 *
 * <p>{@code @Query} takes a JSON FILTER, not JPQL. There is no query language
 * with a FROM clause; you describe the shape of the documents you want. The
 * placeholders are positional ({@code ?0}) rather than named.
 *
 * <p>THERE IS NO JOIN. {@code $lookup} exists in the aggregation framework and
 * is deliberately limited, unindexed on the joined side, and nothing like a
 * relational join. If your query needs one, the document was modelled wrongly -
 * which is why the modelling decision is the whole game.
 *
 * <p>NO TRANSACTIONS ACROSS DOCUMENTS on a single node. MongoDB has supported
 * multi-document transactions since 4.0 and only on a replica set, and the
 * guidance from MongoDB themselves is that needing them often means the schema
 * should be different. The seat-counting rule in EnrollmentService - lock the
 * course, count the enrollments, insert, commit - has no equivalent here that
 * is both correct and simple. That is the concrete answer to the interview
 * question in chapter 33, and it is worth being able to give it in that much
 * detail rather than as "SQL is better for transactions".
 */
@Repository
public interface CourseDocumentRepository extends MongoRepository<CourseDocument, String> {

    /**
     * Derived from the method name, exactly as in the JPA repository. Same
     * mechanism, different store.
     */
    Optional<CourseDocument> findByCodeAndAcademicYear(String code, int academicYear);

    List<CourseDocument> findByAcademicYearOrderByCodeAsc(int academicYear);

    /**
     * A query INTO an embedded array - the thing that has no clean relational
     * equivalent and no join.
     *
     * <p>{@code students.studentNumber} reaches inside the embedded documents
     * with dot notation, and Mongo matches if ANY element of the array matches.
     * In PostgreSQL this is a join to the enrollments table; here it is one
     * read of one collection, because the roster was embedded for exactly this
     * access pattern.
     *
     * <p>Which is the whole lesson about document modelling: the schema is
     * shaped by the QUERIES you intend, not by the entities you have. Model the
     * wrong access pattern and there is no index that saves you - you rewrite
     * the documents.
     */
    @Query("{ 'students.studentNumber': ?0 }")
    List<CourseDocument> findCoursesForStudent(String studentNumber);

    /**
     * A JSON filter with an operator. {@code $lt} is "less than", so this finds
     * courses that are less than a given fraction full.
     *
     * <p>The second argument to {@code @Query} is a PROJECTION - it limits which
     * fields come back, which matters far more here than in SQL because the
     * alternative is transferring the entire embedded roster to answer a question
     * about one number. Selecting columns is an optimisation in SQL; not
     * selecting them in a document store can be the difference between a
     * kilobyte and a megabyte per row.
     *
     * <p>The parameter is a {@code double} because the stored field is a BSON
     * double. Getting that pairing wrong is the trap documented at length on
     * CourseDocument.fillRate, and it fails in two different ways depending on
     * which half you get wrong: silently empty results, or a conversion
     * exception. Neither is obvious from the query.
     */
    @Query(value = "{ 'fillRate': { $lt: ?0 } }",
            fields = "{ 'code': 1, 'title': 1, 'fillRate': 1, 'capacity': 1 }")
    List<CourseDocument> findUnderSubscribed(double fillRateBelow);
}
