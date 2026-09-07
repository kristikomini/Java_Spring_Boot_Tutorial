package it.unicam.cs.enrollment.web.dto;

import it.unicam.cs.enrollment.common.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@code ?page=2&size=50}, gathered into one object.
 *
 * <h2>Why a class rather than two parameters</h2>
 * The alternative is to repeat
 * <pre>
 *   public PageResponse&lt;X&gt; list(&#64;RequestParam(defaultValue = "0") int page,
 *                              &#64;RequestParam(defaultValue = "20") int size)
 * </pre>
 * on every collection endpoint, and to get the defaults subtly wrong on one of
 * them eventually. Spring MVC binds any non-simple handler parameter as a
 * command object: it instantiates the class, matches query parameters to
 * setters by name, and hands it over. So the defaults live in the field
 * initialisers below, in one place, and adding a {@code sort} parameter later
 * changes one class rather than fifteen signatures.
 *
 * <p>The equivalent in the JAX-RS world is {@code @BeanParam} over a class of
 * {@code @QueryParam} fields - same idea, different spelling, and a good
 * example of how much of moving between the two is vocabulary.
 *
 * <h2>The clamping is not here</h2>
 * {@link PageRequest#of} does it, because that is the type the repository takes
 * and the guard needs to hold however the request was built - including from a
 * test or a scheduled job that never went through HTTP. A validation rule that
 * only exists on the web layer protects only the web layer.
 */
public class PaginationParams {

    private int page = 0;

    private int size = PageRequest.DEFAULT_PAGE_SIZE;

    /** For the hand-written repositories, which take the project's own type. */
    public PageRequest toPageRequest() {
        return PageRequest.of(page, size);
    }

    /**
     * For the Spring Data repositories, which take {@link Pageable}.
     *
     * <p>The clamping goes through {@link PageRequest} first rather than being
     * repeated here, so there is exactly one definition of "a page size of
     * 100000 is not a page size" no matter which repository style the caller
     * ends up in.
     *
     * <p>Spring can also inject a {@code Pageable} straight into a handler
     * method - {@code @PageableDefault} sets the defaults - and in a project
     * without the second repository style that is what you would use. It reads
     * {@code ?page=&size=&sort=} out of the request for you, sort included.
     */
    public Pageable toPageable(Sort sort) {
        PageRequest clamped = toPageRequest();
        return org.springframework.data.domain.PageRequest.of(
                clamped.getPageNumber(), clamped.getPageSize(), sort);
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
