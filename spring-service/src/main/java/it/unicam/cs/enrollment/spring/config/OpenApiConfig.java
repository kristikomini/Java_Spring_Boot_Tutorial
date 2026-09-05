package it.unicam.cs.enrollment.spring.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * ============================================================================
 * THE API, DOCUMENTED BY THE API
 * ============================================================================
 * springdoc reads the annotations that are already on the controllers and
 * produces an OpenAPI 3 document. Nothing below is required for that to work -
 * the dependency alone gives you a complete specification. This class only adds
 * the metadata a generator cannot infer: who owns the API, what it is for, and
 * where it runs.
 *
 * <pre>
 *   http://localhost:8281/swagger-ui.html   an interactive console
 *   http://localhost:8281/v3/api-docs       the specification, as JSON
 *   http://localhost:8281/v3/api-docs.yaml  the same, as YAML
 * </pre>
 *
 * <p>THE CONSOLE IS THE PART THAT EARNS ITS PLACE. Fieldbook chapter 12 asks you
 * to provoke every status code with curl; Swagger UI does the same job with a
 * form, and it is what a front-end developer on your team will actually use to
 * find out what your endpoint returns. On a CV, "documented with OpenAPI" is
 * cheap to say and checkable in one click.
 *
 * <p>CODE-FIRST VERSUS DESIGN-FIRST, which is the interview question hiding
 * behind this file. What happens here is code-first: the specification is
 * generated from the implementation, so it cannot drift from it - the failure
 * mode of every hand-maintained API document. The alternative is design-first:
 * the YAML is written and agreed BEFORE either side is built, and the server
 * interfaces and the client are generated from it. Large organisations pick
 * design-first because it lets three teams start on the same day against a
 * contract nobody can quietly change. Small teams pick code-first because it is
 * free. Both are defensible; not knowing the other exists is not.
 *
 * <p>THE SECURITY NOTE. Swagger UI is exposed here because this is a learning
 * stack. On anything reachable from outside, publishing an interactive console
 * that enumerates every endpoint and its parameters is a gift to whoever is
 * scanning you. The usual answer is to disable it by profile
 * ({@code springdoc.swagger-ui.enabled: false} in production) or put it behind
 * authentication. Fieldbook chapter 15 makes the general version of this
 * argument about information disclosure.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI enrollmentApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("UNICAM Course Enrollment API")
                        .version("2.0")
                        .description("""
                                Course catalogue and student enrollment.

                                The same API is served by two independent implementations \
                                against one PostgreSQL schema: a Jakarta EE application on \
                                WildFly (port 8280) and this Spring Boot service (port 8281). \
                                A client should not be able to tell them apart.

                                ## Versioning

                                `/api/v1` is the current contract and is frozen. `/api/v2` \
                                nests the professor as an object instead of two flat fields \
                                and adds `occupiedSeats`. The unversioned `/api` paths are \
                                an alias for v1, kept because the Jakarta EE service serves \
                                them.

                                ## Errors

                                Every failure returns RFC 7807 `application/problem+json` \
                                with a stable `errorCode` and the `correlationId` that ties \
                                the response to the server logs. Branch on `errorCode`, \
                                never on `detail` - the wording may change.
                                """)
                        .contact(new Contact()
                                .name("UNICAM Computer Science")
                                .email("noreply@enrollment.unicam.test"))
                        .license(new License().name("Teaching material")))
                .servers(List.of(
                        new Server().url("http://localhost:8281")
                                .description("Spring Boot implementation"),
                        new Server().url("http://localhost:8280/enrollment")
                                .description("Jakarta EE implementation - same contract")));
    }
}
