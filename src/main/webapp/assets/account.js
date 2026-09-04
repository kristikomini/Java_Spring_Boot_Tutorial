/* ============================================================================
   account.js  -  the small client shared by signin, register and the
                  reserved area
   ============================================================================
   There is no framework here on purpose. The whole surface is four endpoints
   and a cookie; a build step would be more machinery than the thing it builds.

   Everything this file knows about the server is written down in
   docs/ACCOUNTS.md and dissected in fieldbook chapter 15. The two rules that
   matter on the client side:

     1. The session lives in an HttpOnly cookie, so this script CANNOT read it
        and does not try. "Am I signed in" is answered by asking the server -
        GET auth/me - and by nothing else. Any local flag would be a guess that
        goes stale the moment a session is revoked from another tab.

     2. Every state-changing request carries X-Fieldbook-Request. That is the
        CSRF defence: a cross-site <form> cannot set a header at all, and a
        cross-origin fetch that adds one triggers a preflight this server never
        approves. The value is never checked - its presence is the signal.
   ========================================================================= */
(function (global) {
  "use strict";

  /* ------------------------------------------------------------------
     Where the API is.

     Resolved relative to this page rather than hard-coded, so the same
     files work under /enrollment, under / , and behind a proxy that mounts
     the application somewhere else. Opened straight off the filesystem the
     base is a file: URL, the first fetch fails, and every page degrades to
     "this is not being served by the application" rather than hanging.
     ------------------------------------------------------------------ */
  var API = (function () {
    try {
      return new URL("api/fieldbook/", global.location.href).href;
    } catch (e) {
      return null;
    }
  })();

  /**
   * One fetch, one shape of answer: {status, json, retryAfter}.
   *
   * Rejects only when the request never happened - DNS, offline, a blocked
   * origin. Every HTTP status, 500 included, resolves, because "the server
   * said no" and "there was no server" are different problems for the caller
   * and collapsing them into one catch block is how you end up telling
   * somebody their password is wrong when the database is down.
   */
  function call(path, method, body) {
    if (!API) return Promise.reject(new Error("no API"));

    var opts = {
      method: method || "GET",
      credentials: "same-origin",
      headers: {
        "Accept": "application/json",
        "X-Fieldbook-Request": "1"
      }
    };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }

    return fetch(API + path, opts).then(function (r) {
      var retryAfter = parseInt(r.headers.get("Retry-After") || "", 10);
      if (r.status === 204) {
        return { status: r.status, json: null, retryAfter: retryAfter };
      }
      return r.text().then(function (t) {
        var json = null;
        try { json = t ? JSON.parse(t) : null; } catch (e) { /* not JSON */ }
        return { status: r.status, json: json, retryAfter: retryAfter };
      });
    });
  }

  /**
   * Turn a ProblemDetail into one sentence a person can act on.
   *
   * Bean Validation returns every violation rather than only the first, so a
   * form can mark all of its bad fields in one pass; this joins them because
   * these forms have three fields and a list of one would read oddly.
   */
  function problem(res, fallback) {
    var p = res && res.json;
    if (p && Array.isArray(p.violations) && p.violations.length) {
      return p.violations.map(function (v) {
        return v.message || (v.field + " is not valid");
      }).join(" ");
    }
    if (p && p.detail) return p.detail;
    if (p && p.title) return p.title;
    return fallback || "Something went wrong. Try again.";
  }

  /** "in a few minutes" out of a Retry-After in seconds. */
  function retryWording(seconds) {
    if (!seconds || isNaN(seconds)) return "in a few minutes";
    var mins = Math.ceil(seconds / 60);
    return mins <= 1 ? "in about a minute" : "in about " + mins + " minutes";
  }

  /* ------------------------------------------------------------------
     Redirect targets.

     ?next= is attacker-supplied, and an unchecked redirect is a real
     vulnerability rather than a theoretical one: the link starts on a domain
     the victim trusts, so it is the phishing link that survives a careful
     reader. Two checks, because either alone has a bypass:

       - reject anything with a scheme, a backslash, or a leading // - which
         is how "//evil.example" reads as protocol-relative and leaves the site
       - then resolve it and require the same origin anyway, so a browser
         quirk in the first check is not the only thing standing there.
     ------------------------------------------------------------------ */
  function safeNext(raw, fallback) {
    fallback = fallback || "area-riservata.html";
    if (!raw) return fallback;
    if (/^[a-z][a-z0-9+.-]*:/i.test(raw)) return fallback;   // any scheme
    if (/[\\]/.test(raw)) return fallback;                    // backslash
    if (raw.charAt(0) === "/" && raw.charAt(1) === "/") return fallback;
    try {
      var target = new URL(raw, global.location.href);
      if (target.origin !== global.location.origin) return fallback;
      return target.pathname + target.search + target.hash;
    } catch (e) {
      return fallback;
    }
  }

  /* ------------------------------------------------------------------
     Painting

     Everything user-supplied goes in through textContent, never innerHTML.
     That is the same reason the sticky notes are stored as plain text: a name
     reading <img onerror=...> should be a name about an img tag. Escaping by
     construction beats sanitising carefully, because sanitisers have bypasses
     and textContent does not.
     ------------------------------------------------------------------ */
  function say(el, kind, text) {
    if (!el) return;
    el.className = "msg " + (kind || "info");
    el.textContent = text;
    el.hidden = false;
  }

  function hush(el) {
    if (el) el.hidden = true;
  }

  function text(el, value) {
    if (el) el.textContent = value == null ? "" : String(value);
  }

  /** A date a person reads, not an ISO string. Falls back to the raw value. */
  function whenever(iso) {
    if (!iso) return "never";
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    try {
      return d.toLocaleDateString(undefined, {
        year: "numeric", month: "short", day: "numeric"
      });
    } catch (e) {
      return d.toISOString().slice(0, 10);
    }
  }

  /**
   * The learner's own time zone, for the streak.
   *
   * The server stores it per account so that "which day was that" is answered
   * on the reader's calendar rather than the server's. Undefined here is fine
   * - the server falls back to UTC and says so.
   */
  function timeZone() {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
    } catch (e) {
      return null;
    }
  }

  /* ------------------------------------------------------------------
     The course catalogue.

     The server deliberately does not store it - see "Why the client sends the
     course structure" in docs/ACCOUNTS.md. The fieldbook publishes it here
     when it loads, so this page can ask for a percentage over the same
     chapters. Missing simply means the fieldbook has not been opened in this
     browser yet, and the reserved area says so rather than showing a zero it
     cannot justify.
     ------------------------------------------------------------------ */
  function catalogue() {
    try {
      var raw = localStorage.getItem("fieldbook.catalogue.v1");
      var parsed = raw ? JSON.parse(raw) : null;
      if (!parsed || !Array.isArray(parsed.chapters)) return null;
      return parsed;
    } catch (e) {
      return null;   // private browsing, disabled storage, corrupt JSON
    }
  }

  global.Fieldbook = {
    api: API,
    call: call,
    problem: problem,
    retryWording: retryWording,
    safeNext: safeNext,
    say: say,
    hush: hush,
    text: text,
    whenever: whenever,
    timeZone: timeZone,
    catalogue: catalogue,

    /** The floor the server enforces, repeated here so the form can say so. */
    MIN_PASSWORD: 12,

    /**
     * The message shown when the fetch itself failed, or when auth/me answered
     * something other than 200 or 401.
     *
     * Worth its own wording. A 401 means "there is an application here and you
     * are not signed in"; anything else means these files are being served by
     * something that is not the application - a static file server, or the
     * file system - and there is nothing to sign in to. "Check your
     * connection" would be a lie in that case, and a sign-in button that can
     * only fail would be worse.
     */
    OFFLINE: "This page cannot reach the application, so there is nothing to " +
             "sign in to. Open it from the deployed application " +
             "(http://localhost:8280/enrollment/) rather than from a static " +
             "file server or the file system."
  };
})(window);
