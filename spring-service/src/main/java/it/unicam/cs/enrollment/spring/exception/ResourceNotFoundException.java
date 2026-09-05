package it.unicam.cs.enrollment.spring.exception;

/** Becomes a 404. See RestExceptionHandler. */
public class ResourceNotFoundException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public static final String ERROR_CODE = "RESOURCE_NOT_FOUND";

    public ResourceNotFoundException(String message) {
        super(ERROR_CODE, message);
    }

    public static ResourceNotFoundException of(String resourceType, Object identifier) {
        return new ResourceNotFoundException(
                resourceType + " with identifier '" + identifier + "' was not found");
    }
}
