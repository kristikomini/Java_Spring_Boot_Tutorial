package it.unicam.cs.enrollment.fieldbook.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slows down password guessing.
 *
 * <h2>Why a slow hash is not enough on its own</h2>
 * PBKDF2 makes each guess cost the ATTACKER milliseconds if they have stolen
 * the table and are working offline. It does nothing about an attacker who has
 * not stolen anything and is simply posting to the login endpoint - there each
 * guess costs them one HTTP request and costs the server a full PBKDF2. Without
 * a limit, the expensive hash turns from a defence into an amplifier: a
 * hundred requests a second is a denial of service against your own CPU.
 *
 * <p>So there are two separate counters, because there are two separate
 * attacks:
 * <ul>
 *   <li><b>Per account</b> - somebody working through a password list against
 *       one known email. Locked after {@code MAX_PER_ACCOUNT} failures.</li>
 *   <li><b>Per source address</b> - credential stuffing, one attempt each
 *       against thousands of leaked email/password pairs. The per-account
 *       counter never trips, because no single account is tried twice.</li>
 * </ul>
 * Implementing only the first is the common half-measure, and it stops the
 * attack nobody is actually running.
 *
 * <h2>Concurrency, which is the whole reason to read this class</h2>
 * This is an {@code @ApplicationScoped} bean holding mutable state, which is
 * exactly what the fieldbook's CDI chapter tells you never to build. The
 * difference between this and the bug is that every piece of that state is
 * concurrent by construction:
 *
 * <ul>
 *   <li>{@link ConcurrentHashMap}, not {@code HashMap}. A plain
 *       {@code HashMap} written by two threads can corrupt its internal table
 *       and, in older JVMs, spin forever inside {@code get}. Not slow - not
 *       returning.</li>
 *   <li>{@link AtomicInteger}, not {@code int}. {@code count++} is read,
 *       add, write: three operations, so two threads can both read 4 and both
 *       write 5, and one failure is silently lost.</li>
 *   <li>{@code computeIfAbsent} rather than {@code if (get() == null) put()}.
 *       The two-step version has a window between the check and the put in
 *       which another thread does the same thing, and one of the two counters
 *       is then dropped on the floor. This is the check-then-act race, and it
 *       is the single most common concurrency bug in ordinary business code.</li>
 * </ul>
 *
 * <h2>What this deliberately is not</h2>
 * It is in memory, so it resets on redeploy and is per node. On a cluster an
 * attacker gets {@code MAX} attempts per node. The real answer is a shared
 * store - Redis, or the database with a short-lived table - and a rate limiter
 * at the ingress before the request ever reaches the application. This is the
 * cheap version, and cheap is a great deal better than absent.
 */
@ApplicationScoped
public class LoginThrottle {

    /** Failures against one account before it stops accepting attempts. */
    static final int MAX_PER_ACCOUNT = 8;

    /** Failures from one source address before it stops being served. */
    static final int MAX_PER_SOURCE = 30;

    /** How long a block lasts, and how long counters are remembered. */
    static final Duration WINDOW = Duration.ofMinutes(15);

    /**
     * Sweep when the map has grown past this. Entries are tiny, but a map that
     * only ever grows is a memory leak with a slow fuse, and "it was fine for
     * six months" is how those are usually described.
     */
    private static final int SWEEP_ABOVE = 4_000;

    private static final class Counter {
        final AtomicInteger failures = new AtomicInteger();
        volatile Instant firstFailure;
        volatile Instant lastFailure;
    }

    private final Map<String, Counter> byAccount = new ConcurrentHashMap<>();
    private final Map<String, Counter> bySource = new ConcurrentHashMap<>();

    /**
     * Ask before checking a password.
     *
     * @return true when the attempt may proceed
     */
    public boolean allow(String email, String sourceAddress, Instant now) {
        return under(byAccount, key(email), MAX_PER_ACCOUNT, now)
                && under(bySource, key(sourceAddress), MAX_PER_SOURCE, now);
    }

    /** Call after a failed attempt. */
    public void recordFailure(String email, String sourceAddress, Instant now) {
        bump(byAccount, key(email), now);
        bump(bySource, key(sourceAddress), now);
        maybeSweep(now);
    }

    /**
     * Call after a successful one. Clearing the account counter but NOT the
     * source counter is deliberate: an attacker who guesses one password out of
     * a thousand attempts should not get a clean slate for the other 999.
     */
    public void recordSuccess(String email) {
        byAccount.remove(key(email));
    }

    /** How long the caller should be told to wait, for the {@code Retry-After} header. */
    public long retryAfterSeconds(String email, String sourceAddress, Instant now) {
        long a = remaining(byAccount.get(key(email)), now);
        long b = remaining(bySource.get(key(sourceAddress)), now);
        return Math.max(1, Math.max(a, b));
    }

    private static long remaining(Counter c, Instant now) {
        if (c == null || c.lastFailure == null) {
            return 0;
        }
        long left = WINDOW.getSeconds() - Duration.between(c.lastFailure, now).getSeconds();
        return Math.max(0, left);
    }

    private boolean under(Map<String, Counter> map, String key, int max, Instant now) {
        Counter c = map.get(key);
        if (c == null) {
            return true;
        }
        if (expired(c, now)) {
            map.remove(key, c);
            return true;
        }
        return c.failures.get() < max;
    }

    private void bump(Map<String, Counter> map, String key, Instant now) {
        Counter c = map.computeIfAbsent(key, k -> new Counter());
        // A window that has already lapsed is reset rather than continued, so a
        // failure a month ago does not count towards today's total.
        if (expired(c, now)) {
            c.failures.set(0);
            c.firstFailure = now;
        }
        if (c.firstFailure == null) {
            c.firstFailure = now;
        }
        c.lastFailure = now;
        c.failures.incrementAndGet();
    }

    private static boolean expired(Counter c, Instant now) {
        return c.lastFailure == null || Duration.between(c.lastFailure, now).compareTo(WINDOW) > 0;
    }

    private void maybeSweep(Instant now) {
        if (byAccount.size() > SWEEP_ABOVE) {
            byAccount.entrySet().removeIf(e -> expired(e.getValue(), now));
        }
        if (bySource.size() > SWEEP_ABOVE) {
            bySource.entrySet().removeIf(e -> expired(e.getValue(), now));
        }
    }

    private static String key(String raw) {
        return raw == null ? "?" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Test seam. Production code has no reason to empty the counters. */
    void clear() {
        byAccount.clear();
        bySource.clear();
    }
}
