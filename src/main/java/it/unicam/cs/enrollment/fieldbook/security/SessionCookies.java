package it.unicam.cs.enrollment.fieldbook.security;

import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.UriInfo;

import java.util.Map;

/**
 * Builds and reads the one cookie this application sets.
 *
 * <h2>Cookie or {@code localStorage}? The honest answer</h2>
 * The fieldbook's security chapter asks this question and refuses to give a
 * free answer, because there is not one. The two options fail differently:
 *
 * <table>
 *   <caption>Where a session token can live in a browser</caption>
 *   <tr><th></th><th>{@code localStorage}</th><th>{@code HttpOnly} cookie</th></tr>
 *   <tr><td>Readable by JavaScript</td><td>yes - so any XSS steals it</td>
 *       <td>no - the browser attaches it and script cannot see it</td></tr>
 *   <tr><td>Sent automatically</td><td>no - you add a header</td>
 *       <td>yes - which is what enables CSRF</td></tr>
 *   <tr><td>Main risk</td><td>token exfiltration</td><td>cross-site request forgery</td></tr>
 * </table>
 *
 * <p>Neither is "the secure one". The choice here is the cookie, because XSS
 * exfiltration is silent and permanent - the token leaves the machine and you
 * never know - while CSRF is loud, bounded to actions rather than credentials,
 * and has two good mitigations that are applied below and in
 * {@link AuthenticationFilter}.
 *
 * <h2>The three attributes, and what each one stops</h2>
 * <ul>
 *   <li>{@code HttpOnly} - script cannot read the cookie, so an injected script
 *       cannot post the token elsewhere. This is the attribute the choice is
 *       being made for; without it a cookie is strictly worse than
 *       {@code localStorage}, because it has the CSRF exposure as well.</li>
 *   <li>{@code SameSite=Strict} - the browser will not attach the cookie to a
 *       request initiated by another site at all. This is most of the CSRF
 *       defence, done by the browser. The cost is real and worth knowing: a
 *       link from an email into the fieldbook arrives logged out on the first
 *       navigation. For a study tool that is a fine trade; for a site people
 *       reach through links, {@code Lax} plus a CSRF token is the usual
 *       compromise.</li>
 *   <li>{@code Secure} - never sent over plain HTTP, so it cannot be read off
 *       the wire. Set only when the request itself arrived over HTTPS, because
 *       a {@code Secure} cookie issued over {@code http://localhost} is one the
 *       browser accepts and then never sends back, and the resulting "login
 *       does nothing" is a genuinely horrible half hour.</li>
 * </ul>
 *
 * <p>The second half of the CSRF defence is in the filter: a custom request
 * header that a cross-origin form cannot set. Belt and braces, because
 * {@code SameSite} is enforced by the browser and browsers vary.
 */
public final class SessionCookies {

    /** No prefix like {@code __Host-}: that requires {@code Secure}, which
     *  local HTTP development does not have. A production deployment behind
     *  TLS should use it - it is the one cookie attribute an attacker on a
     *  subdomain cannot work around. */
    public static final String NAME = "fb_session";

    /**
     * The header the browser must send on any state-changing request. Its value
     * is irrelevant - what matters is that a plain cross-site
     * {@code <form>} POST cannot set a custom header at all, and a
     * cross-origin {@code fetch} that tries triggers a CORS preflight which
     * this application never approves.
     */
    public static final String CSRF_HEADER = "X-Fieldbook-Request";

    private SessionCookies() {
        // utility class
    }

    /**
     * The {@code Set-Cookie} for a freshly issued session.
     *
     * <p>{@code path} is the application's context root rather than {@code /},
     * so the cookie is not broadcast to every other application deployed on the
     * same server. On a shared host that is the difference between a scoped
     * credential and one that leaks sideways.
     */
    public static NewCookie issue(String rawToken, UriInfo uriInfo) {
        return new NewCookie.Builder(NAME)
                .value(rawToken)
                .path(contextPath(uriInfo))
                .maxAge((int) AuthSession.LIFETIME.getSeconds())
                .httpOnly(true)
                .secure(isSecure(uriInfo))
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    /**
     * The {@code Set-Cookie} that removes it.
     *
     * <p>{@code maxAge = 0} is how a cookie is deleted - there is no delete
     * verb, only an instruction to expire it now. Every attribute except the
     * value must match the cookie being replaced, or the browser treats it as a
     * different cookie and keeps both. Forgetting {@code path} here is the
     * classic reason a logout button appears to do nothing.
     */
    public static NewCookie expire(UriInfo uriInfo) {
        return new NewCookie.Builder(NAME)
                .value("")
                .path(contextPath(uriInfo))
                .maxAge(0)
                .httpOnly(true)
                .secure(isSecure(uriInfo))
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    /** The raw token from the request, or {@code null} if there is no cookie. */
    public static String read(Map<String, Cookie> cookies) {
        if (cookies == null) {
            return null;
        }
        Cookie cookie = cookies.get(NAME);
        if (cookie == null) {
            return null;
        }
        String value = cookie.getValue();
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean isSecure(UriInfo uriInfo) {
        return uriInfo != null
                && uriInfo.getRequestUri() != null
                && "https".equalsIgnoreCase(uriInfo.getRequestUri().getScheme());
    }

    private static String contextPath(UriInfo uriInfo) {
        if (uriInfo == null || uriInfo.getBaseUri() == null) {
            return "/";
        }
        // baseUri is e.g. http://host:8080/enrollment/api/ - the cookie should
        // cover the whole application, page included, so trim back to the
        // context root rather than leaving it scoped to /api.
        String path = uriInfo.getBaseUri().getPath();
        if (path == null || path.isEmpty()) {
            return "/";
        }
        int apiAt = path.indexOf("/api");
        String root = apiAt > 0 ? path.substring(0, apiAt) : path;
        return root.isEmpty() ? "/" : root;
    }
}
