package it.unicam.cs.enrollment.spring.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results, in the SAME shape the Jakarta EE application returns.
 *
 * <p>This class exists for one reason, and it is a reason worth being able to
 * defend in a review: DO NOT SERIALISE org.springframework.data.domain.Page
 * DIRECTLY.
 *
 * <p>It is tempting - a controller can return Page and Jackson will happily
 * produce JSON. But that JSON is an accident of Spring internals, not a designed
 * contract. It carries a "pageable" object with nested sort metadata, plus
 * numberOfElements, plus a top-level "sort", plus fields named "number" and
 * "size" rather than pageNumber and pageSize. Upgrade Spring Data and the shape
 * can change under you; Boot 3.3 started warning about exactly this and offers
 * spring.data.web.pageable.serialization-mode as a mitigation.
 *
 * <p>Worse, it welds your public API to your persistence library. A client
 * written against that JSON now depends on Spring Data being your ORM layer
 * forever. Ten lines of translation buy a wire format you own - which is the
 * same argument the DTO layer makes one level down, applied to pagination.
 *
 * <p>The field names below match the Jakarta EE PageResponse exactly, which is
 * the point: two implementations, one contract.
 */
public record PageResponse<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext) {

    /**
     * Translate a Spring Data Page, mapping each element through a function.
     *
     * <p>The mapper is a parameter rather than something this class knows,
     * because the DTO layer must not depend on the entity layer. That is not
     * pedantry - it is what lets this record be tested with a page of Strings.
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.hasNext());
    }
}
