package it.unicam.cs.enrollment.fieldbook.security;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource whose state-changing methods must carry the custom request
 * header. See {@link CsrfFilter}.
 *
 * <p>Separate from {@link Authenticated} because the two rules protect
 * different things and do not always apply together. Login and registration are
 * anonymous - there is no session to authenticate - and still need CSRF
 * protection, because LOGIN CSRF is a real attack: force a victim browser to
 * log in as the attacker, and everything the victim then does is recorded in an
 * account the attacker can read.
 *
 * <p>Bundling both rules into one annotation would have made that endpoint
 * choose between being protected and being reachable. Two small annotations
 * that compose beat one that has to be all things.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface CsrfProtected {
}
