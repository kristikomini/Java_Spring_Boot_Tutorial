/**
 * ============================================================================
 * THE API CONTRACT, RESTATED IN TYPESCRIPT
 * ============================================================================
 * Every type here mirrors a Java record in the Spring service. Fieldbook
 * chapter 32 says TypeScript is "the easy part for a Java developer" and this
 * file is where that claim is cashed: it is the type system you already think
 * in, bolted onto JavaScript.
 *
 * The translation is almost mechanical:
 *
 *   Java                          TypeScript
 *   ---------------------------   ---------------------------
 *   record CourseResponse(...)    export interface CourseResponse
 *   Long id                       id: number
 *   String code                   code: string
 *   Integer grade  (nullable)     grade?: number
 *   enum EnrollmentStatus         a union of string literals
 *   List<String>                  string[]
 *
 * TWO THINGS THAT DO NOT TRANSLATE, and both matter.
 *
 * `number` is the only numeric type. Java `long` goes past what a JavaScript
 * number can represent exactly (2^53), so an id beyond that arrives silently
 * wrong. Real systems either keep ids below the limit or serialise them as
 * strings. Worth knowing before someone asks why two records have the same id.
 *
 * These interfaces are erased at compile time. They are a promise the COMPILER
 * checks, not the runtime - nothing validates that the JSON actually matches.
 * If the server renames a field, TypeScript is perfectly happy and you get
 * `undefined` at runtime. That is the same shape of problem as Java erasure
 * (chapter 04) arriving from the other side of the wire, and it is why the
 * shared contract needs a test on the SERVER - which is what the jsonPath
 * assertions in CourseControllerTest are.
 */

/**
 * A DISCRIMINATED UNION, and the single most useful TypeScript feature a Java
 * developer does not already have.
 *
 * This is the EnrollmentStatus enum, and the compiler now rejects
 * `status === 'ACTIVEE'` as an error rather than accepting it as a string
 * comparison that is simply always false. Java gets this from the enum type;
 * TypeScript gets it from the union, and the union is strictly more flexible
 * because it composes.
 */
export type EnrollmentStatus = 'ACTIVE' | 'COMPLETED' | 'WITHDRAWN' | 'FAILED';

export type Semester = 'FALL' | 'SPRING';

/** Mirrors CourseResponse.java (v1). */
export interface CourseResponse {
  id: number;
  code: string;
  title: string;
  /** Optional because the column is nullable AND null fields are omitted. */
  description?: string;
  credits: number;
  capacity: number;
  availableSeats: number;
  semester: Semester;
  academicYear: number;
  professorId: number;
  professorName: string;
  enrollmentOpensAt: string;
  enrollmentClosesAt: string;
  enrollmentOpen: boolean;
  /**
   * ABSENT on the list endpoint, present on the detail endpoint.
   *
   * The `?` is doing real work: the server omits the field entirely rather
   * than sending null, so the difference between "not loaded" and "this course
   * has no prerequisites" is visible here. With `strict` on, the compiler makes
   * you handle the undefined case - which is the whole reason the server was
   * written to omit rather than to send an empty array.
   */
  prerequisiteCodes?: string[];
}

/** Mirrors CourseV2Response.java - the nested professor. */
export interface CourseV2Response extends Omit<CourseResponse, 'professorId' | 'professorName'> {
  occupiedSeats: number;
  professor: ProfessorSummary;
}

export interface ProfessorSummary {
  id: number;
  staffNumber: string;
  fullName: string;
  title: string;
  department: string;
}

/** Mirrors EnrollmentResponse.java. */
export interface EnrollmentResponse {
  id: number;
  studentId: number;
  studentNumber: string;
  studentName: string;
  courseId: number;
  courseCode: string;
  courseTitle: string;
  courseCredits: number;
  status: EnrollmentStatus;
  enrolledAt: string;
  completedAt?: string;
  grade?: number;
  withHonours: boolean;
  formattedGrade: string;
}

/** Mirrors EnrollRequest.java. */
export interface EnrollRequest {
  studentId: number;
  courseId: number;
}

/**
 * Mirrors PageResponse.java - and this is why the server does NOT serialise a
 * Spring Data Page directly.
 *
 * A GENERIC interface, exactly like `PageResponse<T>` in Java. TypeScript
 * generics are erased the same way and, unlike Java, have no wildcards to
 * argue about.
 */
export interface PageResponse<T> {
  content: T[];
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  hasNext: boolean;
}

/**
 * Mirrors ProblemDetail.java - RFC 7807.
 *
 * The client branches on `errorCode`, NEVER on `detail`. The detail is prose
 * for a human and may be reworded at any time; the code is the contract. That
 * is the whole reason the server carries both, and typing it here is what makes
 * the rule enforceable in a code review.
 */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  errorCode: string;
  correlationId?: string;
  timestamp: string;
  violations?: Violation[];
}

export interface Violation {
  field: string;
  message: string;
  rejectedValue?: unknown;
}

/**
 * THE THREE STATES EVERY REMOTE CALL HAS, as a discriminated union.
 *
 * Fieldbook chapter 32 golden rule: "every remote call renders three states -
 * loading, error, success. If your component has one branch, it has two bugs."
 * This type makes that rule structural instead of advisory.
 *
 * The `status` field is the DISCRIMINANT. Inside `if (state.status === 'success')`
 * TypeScript narrows the type and `state.data` is available; in the other
 * branches it is a compile error to touch it. The compiler is now enforcing
 * that you cannot render data you have not loaded - which no amount of
 * discipline achieves reliably.
 *
 * There is no Java equivalent before sealed interfaces (Java 17), and with them
 * it is exactly the same idea - chapter 30 covers sealed types as a state
 * machine.
 */
export type RemoteData<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: T }
  | { status: 'error'; problem: ProblemDetail | null; message: string };
