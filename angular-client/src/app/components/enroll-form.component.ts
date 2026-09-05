import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { EnrollmentResponse, ProblemDetail, RemoteData, Violation } from '../models/course.model';
import { CourseService } from '../services/course.service';

/**
 * ============================================================================
 * REACTIVE FORMS - AND VALIDATION THAT RUNS TWICE, DELIBERATELY
 * ============================================================================
 * The validators below mirror the Bean Validation constraints on
 * EnrollRequest.java:
 *
 * <pre>
 *   Java (server)                     Angular (client)
 *   -------------------------------   -------------------------------
 *   {@literal @}NotNull                          Validators.required
 *   {@literal @}Positive                         Validators.min(1)
 *   {@literal @}Size(max = 12)                   Validators.maxLength(12)
 *   {@literal @}Pattern(regexp = "...")          Validators.pattern(...)
 * </pre>
 *
 * THIS DUPLICATION IS THE DESIGN, not an accident, and it is fieldbook chapter
 * 13 arriving from the client side. The client check exists to give a fast
 * answer without a round trip; the SERVER check is the one that cannot be
 * bypassed. Anyone can open the developer console and post whatever they like,
 * so client-side validation is a convenience for honest users and provides
 * exactly zero security.
 *
 * The corollary is the part people get wrong: because the client rules are
 * only a convenience, they may be a SUBSET of the server rules but must never
 * be a SUPERSET. A client that accepts something the server rejects produces a
 * confusing error; a client that rejects something the server would accept
 * makes a feature unreachable with no error at all.
 *
 * And the rules the client cannot check at all - is this course full, has this
 * student passed the prerequisites - are exactly the ones that come back as
 * 409, which is why the response handling below matters more than the
 * validators do.
 */
@Component({
  selector: 'app-enroll-form',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <section class="panel">
      <h2>Enroll a student</h2>

      <!--
        REACTIVE forms, not template-driven. Angular has both:

          template-driven  [(ngModel)] in the HTML, logic in the template
          reactive         the form is an object in the class, typed and testable

        Reactive is what new code uses and what an interview means by "Angular
        forms". The form group below is a plain object a unit test can construct
        and assert on with no DOM at all - the same argument constructor
        injection makes on the server.
      -->
      <form [formGroup]="form" (ngSubmit)="submit()">
        <label>
          Student id
          <input type="number" formControlName="studentId" />
        </label>
        <!--
          The error shows only after the field has been TOUCHED. Without that
          check, every field is red before the user has typed anything, which
          trains people to ignore the colour.
        -->
        @if (form.controls.studentId.touched && form.controls.studentId.invalid) {
          <small class="field-error">A positive student id is required</small>
        }

        <label>
          Course id
          <input type="number" formControlName="courseId" />
        </label>
        @if (form.controls.courseId.touched && form.controls.courseId.invalid) {
          <small class="field-error">A positive course id is required</small>
        }

        <!--
          Disabled while in flight, which prevents the double submit that
          creates two enrollments - or rather, that would if the server did not
          have a unique constraint. It does (chapter 07), so the second request
          gets a 409 instead of a duplicate row. The button state is a
          courtesy; the constraint is the guarantee. Both, always.
        -->
        <button type="submit" [disabled]="form.invalid || submitting()">
          {{ submitting() ? 'Enrolling…' : 'Enroll' }}
        </button>
      </form>

      @switch (state().status) {
        @case ('success') {
          <p class="ok" role="status">
            Enrolled {{ result()?.studentNumber }} in {{ result()?.courseCode }}
            (enrollment #{{ result()?.id }}).
          </p>
        }
        @case ('error') {
          <div class="error" role="alert">
            <!--
              BRANCHING ON errorCode, NOT ON THE MESSAGE.

              This is why the server carries a stable machine-readable code
              beside the human prose. The detail text may be reworded at any
              time and is not a contract; the code is. A client that matches on
              "has reached its capacity" breaks the day somebody improves the
              wording.
            -->
            @switch (problem()?.errorCode) {
              @case ('COURSE_FULL') {
                <p>That course is full. Try another, or check back if someone withdraws.</p>
              }
              @case ('DUPLICATE_RESOURCE') {
                <p>This student is already enrolled in that course.</p>
              }
              @case ('PREREQUISITES_NOT_MET') {
                <p>{{ problem()?.detail }}</p>
              }
              @case ('ENROLLMENT_WINDOW_CLOSED') {
                <p>Enrollment for that course is not open at the moment.</p>
              }
              @default {
                <p>{{ message() }}</p>
              }
            }

            <!--
              FIELD-LEVEL VIOLATIONS from the server, rendered next to the
              summary. The server returns ALL of them rather than failing on the
              first (see ConstraintViolationExceptionMapper and
              RestExceptionHandler), which is only useful if the client actually
              shows them all.
            -->
            @if (violations().length > 0) {
              <ul class="violations">
                @for (violation of violations(); track violation.field) {
                  <li><strong>{{ violation.field }}</strong>: {{ violation.message }}</li>
                }
              </ul>
            }

            @if (problem()?.correlationId; as id) {
              <p class="muted">Reference: <code>{{ id }}</code></p>
            }
          </div>
        }
      }
    </section>
  `,
  styles: [
    `
      .panel { border: 1px solid #ddd; border-radius: 8px; padding: 16px; }
      form { display: grid; gap: 10px; max-width: 320px; }
      label { display: grid; gap: 4px; }
      .field-error { color: #a33; }
      .error { background: #fff4f4; border: 1px solid #f0c0c0; padding: 12px; border-radius: 6px; }
      .ok { background: #f2fbf3; border: 1px solid #bfe3c4; padding: 12px; border-radius: 6px; }
      .violations { margin: 8px 0 0; padding-left: 18px; }
      .muted { color: #666; font-size: 0.9em; }
    `,
  ],
})
export class EnrollFormComponent {
  private readonly courseService = inject(CourseService);
  private readonly fb = inject(FormBuilder);

  /**
   * `nonNullable` makes the control type `number` rather than `number | null`,
   * so `strictTemplates` stops demanding a null check on every read. It is the
   * TypeScript equivalent of the argument chapter 04 makes for Optional: say in
   * the type whether absence is possible, and the compiler does the rest.
   */
  readonly form = this.fb.nonNullable.group({
    studentId: [0, [Validators.required, Validators.min(1)]],
    courseId: [0, [Validators.required, Validators.min(1)]],
  });

  readonly state = signal<RemoteData<EnrollmentResponse>>({ status: 'idle' });

  readonly submitting = computed(() => this.state().status === 'loading');

  readonly result = computed<EnrollmentResponse | null>(() => {
    const s = this.state();
    return s.status === 'success' ? s.data : null;
  });

  readonly problem = computed<ProblemDetail | null>(() => {
    const s = this.state();
    return s.status === 'error' ? s.problem : null;
  });

  readonly message = computed(() => {
    const s = this.state();
    return s.status === 'error' ? s.message : '';
  });

  readonly violations = computed<Violation[]>(() => this.problem()?.violations ?? []);

  submit(): void {
    if (this.form.invalid) {
      // markAllAsTouched, so every error becomes visible at once rather than
      // one per submit attempt. Without it, a user with two bad fields fixes
      // one, submits, and discovers the second.
      this.form.markAllAsTouched();
      return;
    }

    this.state.set({ status: 'loading' });

    // getRawValue(), not value: `value` omits disabled controls, which is a
    // surprise the first time a form has one.
    this.courseService.enroll(this.form.getRawValue()).subscribe({
      next: (enrollment) => this.state.set({ status: 'success', data: enrollment }),
      error: (err: { problem: ProblemDetail | null; message: string }) =>
        this.state.set({ status: 'error', problem: err.problem, message: err.message }),
    });
  }
}
