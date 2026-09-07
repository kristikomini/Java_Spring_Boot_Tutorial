package it.unicam.cs.enrollment.fieldbook.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put this on a controller class or handler method and the request must carry a
 * valid session cookie, or it is answered 401 before your code runs.
 *
 * <h2>Why an annotation rather than a URL pattern</h2>
 * A servlet {@code Filter} is matched by URL pattern, which means the rule
 * lives somewhere other than the code it protects. The failure mode of a URL
 * pattern is a new endpoint that nobody remembered to cover, discovered in
 * production; the failure mode of an annotation is a missing line in the diff
 * being reviewed. Putting the rule on the method makes it visible while reading
 * the method, which is most of the argument.
 *
 * <p>The enforcement is in {@link AuthenticationInterceptor}, a Spring MVC
 * {@code HandlerInterceptor}. An interceptor - unlike a plain servlet filter -
 * is handed the {@code HandlerMethod} that is about to run, so it can read the
 * annotations on it. That is the mechanism that makes this pattern possible at
 * all.
 *
 * <h2>Why not Spring Security</h2>
 * {@code spring-boot-starter-security} with a {@code SecurityFilterChain} and
 * {@code @PreAuthorize} is the standard answer, and in a project of any size it
 * is the right one: it brings session fixation protection, a password encoder,
 * CSRF tokens, method security and a great deal of scrutiny you did not have to
 * pay for.
 *
 * <p>This application does it by hand ON PURPOSE, because the fieldbook's
 * security chapter dissects it: a reader who has written a session check, a
 * throttle and a password hash understands what Spring Security is doing for
 * them, and a reader who has only ever added the starter does not. The cost is
 * stated plainly - this is more code to get right, and in production you would
 * use the framework.
 *
 * <p>Both answers are defensible. Knowing that the choice exists, and what each
 * side costs, is the part that matters.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Authenticated {
}
