package it.unicam.cs.enrollment.fieldbook.security;

import it.unicam.cs.enrollment.fieldbook.domain.AuthSession;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

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
 * {@link CsrfInterceptor}.
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
 * <p>The second half of the CSRF defence is in {@link CsrfInterceptor}: a
 * custom request header that a cross-origin form cannot set. Belt and braces,
 * because {@code SameSite} is enforced by the browser and browsers vary.
 *
 * <h2>Why {@link ResponseCookie} and not {@code jakarta.servlet.http.Cookie}</h2>
 * The servlet {@code Cookie} class has no {@code SameSite} setter - the
 * attribute postdates it, and the usual workaround is to write the
 * {@code Set-Cookie} header by hand and get the formatting wrong. Spring's
 * {@code ResponseCookie} is an immutable builder that renders the header
 * correctly, and its {@code toString()} IS the header value. Reading still uses
 * the servlet type, because that is what arrives on the request.
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
     * <p>{@code path} is the application's context path rather than {@code /},
     * so the cookie is not broadcast to every other application served from the
     * same host. On a shared host that is the difference between a scoped
     * credential and one that leaks sideways.
     */
    public static ResponseCookie issue(String rawToken, HttpServletRequest request) {
        return ResponseCookie.from(NAME, rawToken)
                .path(contextPath(request))
                .maxAge(AuthSession.LIFETIME.getSeconds())
                .httpOnly(true)
                .secure(isSecure(request))
                .sameSite("Strict")
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
    public static ResponseCookie expire(HttpServletRequest request) {
        return ResponseCookie.from(NAME, "")
                .path(contextPath(request))
                .maxAge(0)
                .httpOnly(true)
                .secure(isSecure(request))
                .sameSite("Strict")
                .build();
    }

    /** The raw token from the request, or {@code null} if there is no cookie. */
    public static String read(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Whether the request arrived over TLS.
     *
     * <p>{@code isSecure()} is the servlet container's answer, and behind a
     * reverse proxy it is only correct because the proxy forwarding support is
     * switched on - {@code server.forward-headers-strategy} in
     * {@code application.yml}. Without that, every request looks like plain
     * HTTP to the container, the {@code Secure} flag is never set, and the
     * session cookie travels in clear text on the hop the proxy cannot see.
     */
    private static boolean isSecure(HttpServletRequest request) {
        return request != null && request.isSecure();
    }

    /**
     * The application's context path, which is what the cookie should be
     * scoped to: the page and the API both live under it.
     */
    private static String contextPath(HttpServletRequest request) {
        if (request == null) {
            return "/";
        }
        String path = request.getContextPath();
        return path == null || path.isEmpty() ? "/" : path;
    }
}
