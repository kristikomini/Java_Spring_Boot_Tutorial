import { Component } from '@angular/core';

import { CourseListComponent } from './components/course-list.component';
import { EnrollFormComponent } from './components/enroll-form.component';

/**
 * The root component.
 *
 * `imports` on a STANDALONE component is the part that replaced NgModule
 * declarations: a component lists exactly what its own template uses, and
 * nothing else is visible to it. That is a genuine improvement over the module
 * system, where a component could use anything the module imported and nobody
 * could tell from the file which of those it actually needed.
 *
 * NO ROUTER HERE, deliberately. Two panels on one page is the whole
 * application, and adding a router to prove that Angular has one would be
 * ceremony. The routing story is one line of vocabulary - `provideRouter` with
 * an array of `{path, component}` - and adding it before there is a second page
 * is exactly the over-engineering a junior is most likely to be praised for
 * avoiding.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CourseListComponent, EnrollFormComponent],
  template: `
    <main>
      <h1>UNICAM Enrollment</h1>
      <p class="muted">
        Angular client. Talks to the Spring service on port 8281, which serves the
        same contract as the Jakarta EE application on 8280.
      </p>
      <div class="grid">
        <app-course-list />
        <app-enroll-form />
      </div>
    </main>
  `,
  styles: [
    `
      main { font-family: system-ui, sans-serif; max-width: 960px; margin: 24px auto; padding: 0 16px; }
      .grid { display: grid; gap: 20px; grid-template-columns: 1fr; }
      @media (min-width: 800px) { .grid { grid-template-columns: 3fr 2fr; } }
      .muted { color: #666; }
    `,
  ],
})
export class AppComponent {}
