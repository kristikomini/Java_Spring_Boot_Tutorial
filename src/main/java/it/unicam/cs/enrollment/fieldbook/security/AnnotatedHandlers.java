package it.unicam.cs.enrollment.fieldbook.security;

import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;

/**
 * "Does the handler about to run carry this annotation, on the method or on its
 * controller class?"
 *
 * <p>One method, extracted because both interceptors in this package need it
 * and because getting it wrong is subtle: checking only the method misses a
 * class-level annotation, which is how a whole controller marked
 * {@link Authenticated} would end up unprotected. That bug fails OPEN, silently,
 * and is exactly the sort a security rule must not be able to have.
 *
 * <p>The {@code instanceof} check matters too. A handler is not always a
 * {@link HandlerMethod}: static resources are served by a
 * {@code ResourceHttpRequestHandler}, and the "no handler found" case passes
 * something else again. Anything that is not a controller method carries no
 * annotations, so it is not protected by them - which is correct here, because
 * these interceptors are registered only against the API paths.
 */
final class AnnotatedHandlers {

    private AnnotatedHandlers() {
        // utility class
    }

    static boolean has(Object handler, Class<? extends Annotation> annotation) {
        if (!(handler instanceof HandlerMethod method)) {
            return false;
        }
        return method.getMethodAnnotation(annotation) != null
                || method.getBeanType().isAnnotationPresent(annotation);
    }
}
