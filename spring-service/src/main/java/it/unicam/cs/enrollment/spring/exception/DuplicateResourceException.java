package it.unicam.cs.enrollment.spring.exception;

/** Becomes a 409. See RestExceptionHandler. */
public class DuplicateResourceException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "DUPLICATE_RESOURCE";

    public DuplicateResourceException(String message) {
        super(ERROR_CODE, message);
    }

    public DuplicateResourceException(String message, Throwable cause) {
        super(ERROR_CODE, message, cause);
    }
}
