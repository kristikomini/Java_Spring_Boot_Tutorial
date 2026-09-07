package it.unicam.cs.enrollment.config;

import it.unicam.cs.enrollment.fieldbook.security.AuthenticationInterceptor;
import it.unicam.cs.enrollment.fieldbook.security.CsrfInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Where the fieldbook's two security interceptors are attached to Spring MVC.
 *
 * <h2>Why the registration is separate from the interceptors</h2>
 * An interceptor written as a {@code @Component} is only a bean; nothing runs
 * it until it is registered here. That separation is deliberate and is worth
 * noticing, because it means the URL scope of a rule lives in ONE readable
 * place instead of being scattered across the classes that implement it.
 *
 * <h2>Order matters, and it is the cheap check first</h2>
 * {@link CsrfInterceptor} is added before {@link AuthenticationInterceptor}, so
 * a forged request is refused on a header inspection rather than after a
 * database lookup for a session. More generally: reject on the cheapest
 * evidence available, so that an attacker cannot make you do expensive work by
 * sending rubbish.
 *
 * <h2>Why {@code /api/**} and not {@code /**}</h2>
 * Because the fieldbook page itself, its stylesheet and its script are static
 * resources served by Spring's own resource handler, and they must stay
 * reachable to a signed-out reader - the whole course is readable without an
 * account. Scoping the interceptors to the API is what keeps "the page is
 * public, the data is not" true.
 *
 * <p>The enrollment API under {@code /api/courses} and friends is also
 * deliberately unauthenticated: it is the specimen the fieldbook's security
 * chapter dissects, and adding auth to it would remove the exercise. The
 * interceptors see those requests and let them through, because those handlers
 * carry neither annotation.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CsrfInterceptor csrf;
    private final AuthenticationInterceptor authentication;

    public WebConfig(CsrfInterceptor csrf, AuthenticationInterceptor authentication) {
        this.csrf = csrf;
        this.authentication = authentication;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(csrf).addPathPatterns("/api/**");
        registry.addInterceptor(authentication).addPathPatterns("/api/**");
    }
}
