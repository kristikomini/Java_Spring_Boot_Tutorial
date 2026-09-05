import { Component, OnInit, computed, inject, signal } from '@angular/core';

import { CourseResponse, ProblemDetail, RemoteData } from '../models/course.model';
import { CourseService } from '../services/course.service';

/**
 * ============================================================================
 * A STANDALONE COMPONENT WITH SIGNALS - MODERN ANGULAR
 * ============================================================================
 * Fieldbook chapter 32 has a six-row table translating React to Angular. This
 * file is that table as working code.
 *
 * <pre>
 *   React                        Angular
 *   --------------------------   ------------------------------------
 *   function Component()         {@literal @}Component class
 *   props                        {@literal @}Input() / input()
 *   useState                     signal()
 *   useMemo                      computed()
 *   useEffect                    ngOnInit / ngOnDestroy, or effect()
 *   fetch                        HttpClient (an Observable, not a promise)
 *   context                      {@literal @}Injectable service + DI
 * </pre>
 *
 * TWO THINGS HERE ARE NEWER THAN MOST TUTORIALS YOU WILL FIND, and both matter
 * if the chapter is not to date itself.
 *
 * STANDALONE. There is no NgModule anywhere in this application. Until Angular
 * 14 every component had to be declared in a module, and that ceremony is what
 * gave Angular its reputation for being heavy. Standalone components are the
 * default from Angular 17 and the only style in new code - but the majority of
 * Angular material online predates them, so recognising `@NgModule` as legacy
 * is itself worth knowing.
 *
 * SIGNALS. A signal is a value that knows who is reading it. When it changes,
 * exactly the parts of the template that read it are updated - rather than
 * Angular re-checking the whole component tree, which is what zone.js-based
 * change detection did. Read one by CALLING it: `courses()`, not `courses`.
 * Forgetting the parentheses in a template is the most common signals mistake
 * and shows up as `[object Object]` on the page.
 *
 * THE JAVA PARALLEL WORTH DRAWING: a signal is closer to an observable property
 * than to a field. `computed()` is a derived value that recalculates only when
 * something it read has changed - a memoised getter with automatic dependency
 * tracking.
 */
@Component({
  selector: 'app-course-list',
  standalone: true,
  template: `
    <section class="panel">
      <header>
        <h2>Courses</h2>
        <label>
          Academic year
          <select [value]="year()" (change)="onYearChange($event)">
            <option [value]="2025">2025</option>
            <option [value]="2026">2026</option>
          </select>
        </label>
      </header>

      <!--
        THE THREE STATES, ENFORCED BY THE TYPE SYSTEM.

        @switch on state().status is exhaustive over the RemoteData union, so
        adding a fourth state to the type breaks this template at compile time
        with strictTemplates on. Chapter 32 golden rule two - "if your component
        has one branch, it has two bugs" - made structural instead of advisory.

        The @if / @for / @switch blocks are Angular 17+ control flow. The older
        *ngIf / *ngFor directives do the same job and are what you will meet in
        existing codebases; this syntax needs no imports and type-narrows
        correctly, which the directives never did.
      -->
      @switch (state().status) {
        @case ('loading') {
          <p class="muted" role="status">Loading courses…</p>
        }
        @case ('error') {
          <div class="error" role="alert">
            <p>{{ errorMessage() }}</p>
            <!--
              The correlation id, shown to the user. This is the whole point of
              CorrelationIdFilter on the server: a user can read this string out
              to support, and an engineer greps one log for it.
            -->
            @if (errorProblem()?.correlationId; as id) {
              <p class="muted">Reference: <code>{{ id }}</code></p>
            }
            <button type="button" (click)="load()">Try again</button>
          </div>
        }
        @case ('success') {
          @if (courses().length === 0) {
            <!--
              THE EMPTY STATE, which is the fourth state everybody forgets. A
              successful response with no rows is not an error and must not
              render as a blank panel that looks broken.
            -->
            <p class="muted">No courses for {{ year() }}.</p>
          } @else {
            <p class="muted">{{ summary() }}</p>
            <ul class="courses">
              @for (course of courses(); track course.id) {
                <li [class.full]="course.availableSeats === 0">
                  <div class="row">
                    <strong>{{ course.code }}</strong>
                    <span>{{ course.title }}</span>
                  </div>
                  <div class="row muted">
                    <span>{{ course.professorName }}</span>
                    <span>{{ course.credits }} CFU</span>
                    <span>
                      {{ course.availableSeats }} / {{ course.capacity }} seats free
                    </span>
                    @if (!course.enrollmentOpen) {
                      <span class="badge">closed</span>
                    }
                  </div>
                </li>
              }
            </ul>
          }
        }
      }
    </section>
  `,
  styles: [
    `
      .panel { border: 1px solid #ddd; border-radius: 8px; padding: 16px; }
      header { display: flex; justify-content: space-between; align-items: center; }
      .courses { list-style: none; padding: 0; }
      .courses li { padding: 10px 0; border-bottom: 1px solid #eee; }
      .courses li.full { opacity: 0.55; }
      .row { display: flex; gap: 12px; align-items: baseline; }
      .muted { color: #666; font-size: 0.9em; }
      .error { background: #fff4f4; border: 1px solid #f0c0c0; padding: 12px; border-radius: 6px; }
      .badge { background: #eee; border-radius: 4px; padding: 1px 6px; font-size: 0.8em; }
    `,
  ],
})
export class CourseListComponent implements OnInit {
  private readonly courseService = inject(CourseService);

  /**
   * The whole remote state in ONE signal, not three booleans.
   *
   * `loading`, `error` and `data` as separate fields permits states that make
   * no sense - loading AND error at once, data present while loading is true -
   * and every component then has to defend against combinations that should be
   * impossible. A discriminated union has exactly four legal values. This is
   * chapter 04 argument about making illegal states unrepresentable, applied on
   * the front end.
   */
  readonly state = signal<RemoteData<CourseResponse[]>>({ status: 'idle' });

  readonly year = signal(2026);

  /**
   * `computed()` derives from other signals and recalculates only when one of
   * them actually changed. Angular tracks the dependency automatically because
   * reading `state()` inside the function registers it.
   *
   * The narrowing inside is the discriminated union paying off: after the
   * `status === 'success'` check, TypeScript knows `s.data` exists. Outside it,
   * touching `s.data` is a compile error rather than a runtime `undefined`.
   */
  readonly courses = computed(() => {
    const s = this.state();
    return s.status === 'success' ? s.data : [];
  });

  readonly errorMessage = computed(() => {
    const s = this.state();
    return s.status === 'error' ? s.message : '';
  });

  readonly errorProblem = computed<ProblemDetail | null>(() => {
    const s = this.state();
    return s.status === 'error' ? s.problem : null;
  });

  readonly summary = computed(() => {
    const list = this.courses();
    const open = list.filter((c) => c.enrollmentOpen).length;
    const seats = list.reduce((total, c) => total + c.availableSeats, 0);
    return `${list.length} course(s), ${open} open, ${seats} seats free`;
  });

  /**
   * `ngOnInit`, not the constructor.
   *
   * The same rule as chapter 04 gives for construction order in Java: a
   * constructor should build the object, not start work. Angular has a stronger
   * version of it - inputs are not bound yet when the constructor runs, so a
   * component that fetches there is reading properties that have not arrived.
   *
   * The React equivalent is `useEffect(..., [])`.
   */
  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.set({ status: 'loading' });

    this.courseService.listCourses(this.year()).subscribe({
      next: (page) => this.state.set({ status: 'success', data: page.content }),
      error: (err: { problem: ProblemDetail | null; message: string }) =>
        this.state.set({ status: 'error', problem: err.problem, message: err.message }),
    });

    /*
     * ON UNSUBSCRIBING, which chapter 32 lists as the genuinely new concept.
     *
     * An Observable is lazy and can emit many times, so a subscription is a
     * resource - and a component destroyed with a live subscription leaks it,
     * along with everything the callback closed over.
     *
     * HttpClient observables are the exception: they complete after one
     * emission, which unsubscribes automatically. So this particular call is
     * safe without cleanup, and that is exactly why it is a trap - people learn
     * "you do not need to unsubscribe from HttpClient", generalise it to a
     * router or form-value stream that never completes, and leak.
     *
     * The three real answers, in the order a codebase should reach for them:
     *   1. the `async` pipe in the template - Angular unsubscribes for you
     *   2. `takeUntilDestroyed()` - the modern operator, needs no boilerplate
     *   3. an ngOnDestroy with a Subject - what you will see in older code
     */
  }

  onYearChange(event: Event): void {
    this.year.set(Number((event.target as HTMLSelectElement).value));
    this.load();
  }
}
