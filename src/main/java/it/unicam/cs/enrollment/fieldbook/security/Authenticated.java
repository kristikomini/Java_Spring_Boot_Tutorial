package it.unicam.cs.enrollment.fieldbook.security;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put this on a resource class or method and the request must carry a valid
 * session cookie, or it is answered 401 before your code runs.
 *
 * <h2>What {@code @NameBinding} does</h2>
 * A JAX-RS filter is global by default: {@code @Provider} alone means "run on
 * every request", which for an authentication filter would lock the login
 * endpoint against everybody including people trying to log in. {@code @NameBinding}
 * turns the annotation below into a label. The filter carries the same label,
 * and the runtime then applies it to exactly the resources that carry it too.
 *
 * <p>This is the JAX-RS equivalent of a Servlet filter's URL pattern, and it is
 * better in the way that matters: the rule lives on the method it protects, so
 * it is visible while reading the method, and adding an endpoint cannot
 * accidentally leave it outside the pattern. The failure mode of a
 * {@code web.xml} URL pattern is a new endpoint that nobody remembered to
 * cover; the failure mode here is a missing annotation, which at least sits in
 * the diff being reviewed.
 *
 * <h2>Why not {@code @RolesAllowed}</h2>
 * {@code @RolesAllowed} is the standard answer and would be the right one in a
 * project using container-managed identity - a security domain configured in
 * WildFly, users in an LDAP directory or a JDBC realm. This application owns
 * its own account table and deliberately does not depend on server
 * configuration to work, so authentication is done in application code. The
 * price is that this filter is one more thing that must be correct; the benefit
 * is that the WAR deploys to any Jakarta EE 11 server with no server-side
 * setup at all.
 *
 * <p>Both answers are defensible. Knowing that the choice exists, and what each
 * side costs, is the part that matters.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Authenticated {
}
