package it.unicam.cs.enrollment.spring.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * What a course looks like on the wire.
 *
 * <p>A RECORD, where the Jakarta EE version is a 130-line class of private
 * fields, getters and setters. The JSON Jackson produces from this is identical
 * to what Yasson produces from that - same field names, same order, same types -
 * so the API contract is unchanged and only the Java shrank.
 *
 * <p>This is fieldbook chapter 30 (records) meeting chapter 13 (the boundary),
 * and a response DTO is the textbook case for one: it is created once, read
 * many times, and never mutated. The record gives you the constructor, the
 * accessors, equals, hashCode and toString, and - the part that matters - makes
 * the fields final so nothing downstream can quietly edit a response after the
 * mapper built it.
 *
 * <p>TWO THINGS TO KNOW BEFORE USING RECORDS AS DTOs, because both come up:
 *
 * <p>Jackson has deserialised records since 2.12 and reads the component names
 * from the bytecode, so no {@code @JsonProperty} is needed. Older stacks needed
 * {@code -parameters} on the compiler; the Spring Boot parent sets it for you,
 * which is why this works without the {@code <arg>-parameters</arg>} the root
 * POM has to ask for by hand.
 *
 * <p>A record cannot extend anything, so if your DTOs share a base class,
 * records are not a drop-in. That is usually a sign the base class was a mistake
 * rather than a reason to keep it.
 *
 * <p>{@code availableSeats} is computed by the mapper rather than stored,
 * because it is a fact about the enrollments table and not about this row. The
 * alternative - a counter column on courses - is the classic denormalisation
 * that goes wrong the first time an enrollment is deleted by hand.
 */
public record CourseResponse(
        Long id,
        String code,
        String title,
        String description,
        int credits,
        int capacity,
        long availableSeats,
        String semester,
        int academicYear,
        Long professorId,
        String professorName,
        Instant enrollmentOpensAt,
        Instant enrollmentClosesAt,
        boolean enrollmentOpen,

        /**
         * Null on the list endpoint, populated on the detail endpoint. Null
         * rather than an empty list, deliberately: with
         * {@code default-property-inclusion: non_null} the field then disappears
         * from the list response entirely, which says "not loaded" rather than
         * the lie "this course has no prerequisites".
         */
        List<String> prerequisiteCodes) {
}
