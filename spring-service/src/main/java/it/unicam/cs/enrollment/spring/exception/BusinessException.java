package it.unicam.cs.enrollment.spring.exception;

/**
 * The root of the application-specific exception hierarchy, carried over
 * unchanged from the Jakarta EE application.
 *
 * <p>It extends RuntimeException, and that is a decision rather than laziness.
 * Fieldbook chapter 04 puts it this way: a checked exception is a statement that
 * the immediate caller can do something about the failure. Nothing in a
 * controller can recover from "this course is full" - it can only translate it
 * into a status code - so forcing every layer in between to declare or wrap it
 * buys nothing and costs a throws clause on every method.
 *
 * <p>The unchecked choice also matters for transactions. BOTH
 * jakarta.transaction.Transactional and
 * org.springframework.transaction.annotation.Transactional roll back on
 * unchecked exceptions only, by default. A checked exception thrown out of a
 * transactional method COMMITS the transaction unless you say otherwise
 * (rollbackOn there, rollbackFor here). Making these unchecked means the
 * rollback happens for the reason you expect, in both frameworks.
 *
 * <p>The errorCode is a stable, machine-readable string. The message is for a
 * human and may be reworded at any time; the code is a contract a client may
 * branch on. Keeping both is what lets the error response be useful to a person
 * reading logs AND to a front end deciding which form field to highlight.
 */
public abstract class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    protected BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected BusinessException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
