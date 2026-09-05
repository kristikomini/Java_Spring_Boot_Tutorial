package it.unicam.cs.enrollment.spring.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ============================================================================
 * CORS - the header that makes the Angular client work
 * ============================================================================
 * The client runs on http://localhost:4280 and this API on 8281. Different port
 * means a different ORIGIN, and a browser will not hand a cross-origin response
 * to JavaScript unless the server says that origin is allowed.
 *
 * <p>Fieldbook chapter 32 poses this exactly: the request is made, the server
 * log shows 200, and the console says the request was blocked. CORS is enforced
 * on the CLIENT side, which is why the server side looks perfectly healthy and
 * why the answer is never in the server log.
 *
 * <p>THE PREFLIGHT. A POST with {@code Content-Type: application/json} is not a
 * "simple" request, so the browser first sends an OPTIONS request asking whether
 * the real one is permitted. That preflight must also be answered - which is
 * what allowedMethods below does, and which is why "my GET works but my POST
 * does not" is such a common report.
 *
 * <p>NO WILDCARD, and this is the part that matters. {@code allowedOrigins("*")}
 * makes the error go away and lets ANY website on the internet call this API
 * using a visitor browser. With credentials enabled the specification forbids it
 * outright; without them it is still an open door. Chapter 15 argues this at
 * length. The origins here are exactly the two Angular dev server ports.
 *
 * <p>{@code @Profile("demo")} means this configuration does not exist in
 * production at all. That is deliberate: a browser is not expected to call the
 * production API cross-origin, and configuration needed only for local
 * development should be present only for local development.
 */
@Configuration
@Profile("demo")
public class CorsConfig implements WebMvcConfigurer {

    @Value("${enrollment.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                // The correlation id is a request header the client sets, so it
                // has to be allowed explicitly or the preflight fails.
                .allowedHeaders("Content-Type", "X-Correlation-Id")
                // And it is a response header the client wants to READ, which
                // needs exposedHeaders - a separate list, and the one people
                // forget. Without it the header arrives and JavaScript cannot
                // see it.
                .exposedHeaders("X-Correlation-Id", "Location")
                .maxAge(3600);
    }
}
