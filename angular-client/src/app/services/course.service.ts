import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, throwError } from 'rxjs';

import {
  CourseResponse,
  EnrollRequest,
  EnrollmentResponse,
  PageResponse,
  ProblemDetail,
} from '../models/course.model';

/**
 * ============================================================================
 * @Injectable - THE ONE PART OF ANGULAR THAT FEELS IMMEDIATELY FAMILIAR
 * ============================================================================
 * Angular has a real dependency-injection container, and this is a singleton
 * service registered in it. Fieldbook chapter 32 says that is precisely why
 * Italian enterprise shops standardised on Angular: it looks and behaves like
 * Spring, on the other side of the wire.
 *
 * <pre>
 *   Spring                          Angular
 *   -----------------------------   -----------------------------
 *   {@literal @}Service                        {@literal @}Injectable({providedIn: 'root'})
 *   constructor injection           inject() or a constructor
 *   RestClient / WebClient          HttpClient
 *   singleton by default            'root' means one per application
 * </pre>
 *
 * `providedIn: 'root'` is the equivalent of a `@Service` bean in the root
 * context: one instance for the whole application, created lazily, and
 * tree-shaken away entirely if nothing injects it.
 *
 * `inject()` rather than a constructor parameter is the modern Angular style.
 * Both work; inject() composes better and is what new code uses.
 *
 * THE RULE THAT MATTERS: HTTP LIVES HERE, NOT IN A COMPONENT. Exactly the same
 * argument as chapter 12 makes about a JAX-RS resource being "a thin
 * translator" - a component that calls HttpClient directly cannot be tested
 * without HTTP, and the same call gets copied into the second component that
 * needs it.
 */
@Injectable({ providedIn: 'root' })
export class CourseService {
  private readonly http = inject(HttpClient);

  /**
   * The Spring service, not the WildFly one - but both serve this contract, so
   * changing this line to `http://localhost:8280/enrollment/api` should change
   * nothing the user can see. That is the actual test of whether the two
   * implementations are one API, and it is worth doing once by hand.
   *
   * Hardcoded here for clarity. A real application puts it in
   * `src/environments/`, which is the Angular equivalent of a Spring profile.
   */
  private readonly baseUrl = 'http://localhost:8281/api/v1';

  /**
   * GET /api/v1/courses
   *
   * `HttpParams` is IMMUTABLE - `set()` returns a NEW instance rather than
   * mutating. Writing `params.set('year', '2026')` and ignoring the result is a
   * silent no-op and a genuinely common bug, exactly like calling
   * `String.trim()` without assigning it in Java.
   *
   * The generic argument `<PageResponse<CourseResponse>>` is a PROMISE, not a
   * check. Angular does not validate the JSON against it - see the note in
   * course.model.ts. It buys editor completion and compile-time safety in the
   * code that consumes it, which is worth a great deal and is not the same as
   * validation.
   */
  listCourses(year: number, page = 0, size = 20): Observable<PageResponse<CourseResponse>> {
    const params = new HttpParams()
      .set('year', year)
      .set('page', page)
      .set('size', size);

    return this.http
      .get<PageResponse<CourseResponse>>(`${this.baseUrl}/courses`, { params })
      .pipe(catchError(this.toProblem));
  }

  /** GET /api/v1/courses/open */
  listOpenCourses(): Observable<CourseResponse[]> {
    return this.http
      .get<CourseResponse[]>(`${this.baseUrl}/courses/open`)
      .pipe(catchError(this.toProblem));
  }

  /**
   * GET /api/v1/courses/{id}
   *
   * `map` demonstrates the operator every RxJS pipeline uses. The detail
   * endpoint returns prerequisiteCodes; the list endpoint omits it. Defaulting
   * to `[]` here is deliberate and slightly wrong on purpose - see the comment
   * on the model: the server distinguishes "not loaded" from "none", and
   * flattening that distinction in the service means a component can no longer
   * tell them apart. It is done here because this method only ever calls the
   * detail endpoint, where the field IS loaded, so the ambiguity cannot arise.
   */
  getCourse(id: number): Observable<CourseResponse> {
    return this.http.get<CourseResponse>(`${this.baseUrl}/courses/${id}`).pipe(
      map((course) => ({ ...course, prerequisiteCodes: course.prerequisiteCodes ?? [] })),
      catchError(this.toProblem),
    );
  }

  /**
   * POST /api/v1/enrollments
   *
   * This is the call that can return 409 COURSE_FULL, 409 DUPLICATE_RESOURCE,
   * 400 VALIDATION_FAILED or 404 RESOURCE_NOT_FOUND. Every one of those is a
   * normal outcome the user interface has to render, not an exception.
   */
  enroll(request: EnrollRequest): Observable<EnrollmentResponse> {
    return this.http
      .post<EnrollmentResponse>(`${this.baseUrl}/enrollments`, request)
      .pipe(catchError(this.toProblem));
  }

  /**
   * ============================================================================
   * TURNING AN HTTP FAILURE BACK INTO THE SERVER OWN ERROR CONTRACT
   * ============================================================================
   * THE FETCH TRAP, AND WHY HttpClient DOES NOT HAVE IT. Chapter 32 golden rule
   * one: `fetch` does not throw on 4xx or 5xx, so a try/catch around it renders
   * a success page for your 409. Angular HttpClient is the opposite - it routes
   * any non-2xx into the error channel, which is why this method exists at all
   * and why an Angular codebase does not usually contain that bug.
   *
   * What arrives is an `HttpErrorResponse`, whose `error` property holds the
   * PARSED BODY. Because the server sends RFC 7807, that body is a
   * ProblemDetail - so the client gets `errorCode`, the field-level
   * `violations`, and the `correlationId` that ties this exact failure to a
   * server log line. A user can read that id out to support.
   *
   * That is the payoff for the whole error-handling design on the server side:
   * a client can behave differently for COURSE_FULL than for
   * DUPLICATE_RESOURCE, without parsing prose.
   *
   * `status === 0` is the case people forget. It means the request never got an
   * answer - the server is down, DNS failed, the network dropped, or CORS
   * blocked it. There is no ProblemDetail because there was no response, so the
   * client must produce its own message. Chapter 32 has the CORS console
   * question; this is the branch that fires when it happens.
   */
  private toProblem(error: HttpErrorResponse): Observable<never> {
    if (error.status === 0) {
      return throwError(() => ({
        problem: null,
        message:
          'Could not reach the enrollment service. It may be down, or this may be CORS - ' +
          'check the browser console for a blocked request.',
      }));
    }

    const problem = error.error as ProblemDetail | null;

    return throwError(() => ({
      problem,
      // The server prose if there is any, and a fallback if the body was not a
      // ProblemDetail at all - which happens for a 502 from a proxy in front,
      // or any failure that never reached the application.
      message: problem?.detail ?? `The request failed with status ${error.status}.`,
    }));
  }
}
