import { HttpInterceptorFn } from '@angular/common/http';

/**
 * ============================================================================
 * THE CORRELATION ID, PROPAGATED FROM THE BROWSER
 * ============================================================================
 * CorrelationIdFilter on the server generates an id when the client does not
 * send one. This interceptor sends one - which is strictly better, because the
 * id now covers the WHOLE journey: the click, the request, the server work, and
 * the log line. A user reporting "it failed at 14:32" becomes a single grep.
 *
 * A functional interceptor (Angular 15+) is a plain function. Older code uses a
 * class implementing HttpInterceptor, and you will meet plenty of it; both do
 * the same job.
 *
 * THE SERVER SANITISES THIS VALUE, and it is worth understanding why rather
 * than assuming the client is trusted. This header goes straight into a log
 * file, so a newline in it lets an attacker forge whole log lines and make
 * their own activity look like a routine health check. CorrelationIdFilter
 * strips everything outside a safe alphabet. The client being well-behaved is
 * irrelevant - the SERVER cannot know which client sent the request, and
 * anything from the network is input.
 *
 * WHAT AN INTERCEPTOR IS FOR, generally, and why it is the right place: it is
 * the client-side equivalent of a servlet filter. Cross-cutting concerns that
 * belong on every request - an auth token, a correlation id, a retry policy, a
 * loading indicator - live here rather than being copied into every service
 * method.
 */
export const correlationInterceptor: HttpInterceptorFn = (req, next) => {
  const correlationId = crypto.randomUUID().slice(0, 8);

  // Requests are IMMUTABLE. `req.clone()` returns a new one; mutating
  // `req.headers` does nothing at all and is a silent no-op, exactly like
  // HttpParams.set in the service.
  return next(
    req.clone({
      setHeaders: { 'X-Correlation-Id': correlationId },
    }),
  );
};
