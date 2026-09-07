package it.unicam.cs.enrollment.web;

import it.unicam.cs.enrollment.domain.model.Student;
import it.unicam.cs.enrollment.domain.model.StudentStatus;
import it.unicam.cs.enrollment.exception.InvalidRequestException;
import it.unicam.cs.enrollment.service.EnrollmentService;
import it.unicam.cs.enrollment.service.StudentService;
import it.unicam.cs.enrollment.web.dto.CreateStudentRequest;
import it.unicam.cs.enrollment.web.dto.EnrollmentResponse;
import it.unicam.cs.enrollment.web.dto.PageResponse;
import it.unicam.cs.enrollment.web.dto.PaginationParams;
import it.unicam.cs.enrollment.web.dto.StudentResponse;
import it.unicam.cs.enrollment.web.dto.UpdateStudentRequest;
import it.unicam.cs.enrollment.web.mapper.EnrollmentMapper;
import it.unicam.cs.enrollment.web.mapper.StudentMapper;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Students: the CRUD half of the API.
 *
 * <h2>What a {@code @RestController} actually is</h2>
 * {@code @RestController} is {@code @Controller} plus {@code @ResponseBody} on
 * every method - which is to say "whatever you return, serialise it as the
 * response body" rather than "treat it as the name of a view template". That
 * one annotation is the whole difference between a REST API and a server-side
 * rendered page in Spring MVC.
 *
 * <h2>Return the DTO, or return {@code ResponseEntity}?</h2>
 * Both are used below, and the rule of thumb is worth stating:
 * <ul>
 *   <li>return the DTO when the status is always 200 - it is more readable and
 *       self-documenting, and the method signature tells you the shape;</li>
 *   <li>return {@code ResponseEntity} when you need to choose a status or set a
 *       header, as {@link #create} does with {@code Location}.</li>
 * </ul>
 * The 404 case needs neither: the service throws, and
 * {@code RestExceptionHandler} produces the status.
 */
@RestController
@RequestMapping(path = {"/api/students", "/api/v1/students"},
        produces = MediaType.APPLICATION_JSON_VALUE)
public class StudentController {

    private final StudentService studentService;
    private final EnrollmentService enrollmentService;
    private final StudentMapper studentMapper;
    private final EnrollmentMapper enrollmentMapper;

    public StudentController(StudentService studentService,
                             EnrollmentService enrollmentService,
                             StudentMapper studentMapper,
                             EnrollmentMapper enrollmentMapper) {
        this.studentService = studentService;
        this.enrollmentService = enrollmentService;
        this.studentMapper = studentMapper;
        this.enrollmentMapper = enrollmentMapper;
    }

    // ==================================================================
    // POST /api/students
    // ==================================================================

    /**
     * Registers a new student.
     *
     * <h3>Why 201 and not 200</h3>
     * {@code 201 Created} is the correct status when a request creates a new
     * resource, and it MUST be accompanied by a {@code Location} header pointing
     * at it. Clients (and generated SDKs) rely on this: it tells them the URI of
     * the thing they just made without having to guess how to build it.
     *
     * <h3>{@code @Valid} - where validation happens</h3>
     * The annotation makes Spring run Bean Validation on the deserialised body
     * BEFORE this method executes. If a constraint fails, a
     * {@code MethodArgumentNotValidException} is thrown and never reaches here;
     * {@code RestExceptionHandler} turns it into a 400 listing every offending
     * field. Validation you cannot forget to call is worth more than validation
     * you have to remember.
     *
     * <p>Note the pairing: {@code @Valid} without {@code @RequestBody} silently
     * validates nothing on a body, and {@code @RequestBody} without
     * {@code @Valid} silently accepts anything that parses. Neither mistake
     * produces an error - which is precisely why both are worth checking for in
     * review.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StudentResponse> create(@Valid @RequestBody CreateStudentRequest request) {
        Student created = studentService.create(studentMapper.toCommand(request));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(studentMapper.toSummaryResponse(created));
    }

    // ==================================================================
    // GET /api/students
    // ==================================================================

    /**
     * Searches students, paginated.
     *
     * <h3>Parsing the status parameter by hand</h3>
     * Spring can convert a {@code @RequestParam} straight into an enum. We do
     * not let it, because when the value is invalid the conversion failure
     * surfaces as a generic 400 whose message mentions
     * {@code ConversionFailedException} and the fully qualified enum class -
     * accurate, and no help at all to whoever sent {@code ?status=BANANA}.
     *
     * <p>Taking the parameter as a String and converting it ourselves lets us
     * return a 400 that NAMES the legal values. Knowing where a framework's
     * default behaviour is unhelpful, and quietly correcting it, is a large part
     * of building an API people enjoy using.
     *
     * <p>400, not 409: {@code BANANA} is not a status and never will be, so the
     * request is malformed rather than in conflict with the current state. See
     * {@link InvalidRequestException}.
     *
     * <h3>The sort lives here, not in the query</h3>
     * {@code Sort} is passed into the repository through {@code Pageable}, so
     * the same query serves a differently ordered list without a second method.
     * Sorting by last name then first name - rather than by id - because this
     * is a list a human reads.
     */
    @GetMapping
    public PageResponse<StudentResponse> search(
            @RequestParam(name = "name", required = false) String nameFragment,
            @RequestParam(name = "status", required = false) String status,
            PaginationParams pagination) {

        StudentStatus parsedStatus = parseStatus(status);

        Page<Student> page = studentService.search(
                nameFragment,
                parsedStatus,
                pagination.toPageable(Sort.by("lastName", "firstName")));

        return PageResponse.from(page, studentMapper::toSummaryResponse);
    }

    private StudentStatus parseStatus(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return StudentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // The valid values are derived from the enum itself, so adding a
            // constant can never leave a hand-written list out of date.
            throw InvalidRequestException.invalidEnumValue("status", raw, StudentStatus.class);
        }
    }

    // ==================================================================
    // GET /api/students/{id}
    // ==================================================================

    /** One student with their full transcript. */
    @GetMapping("/{id}")
    public StudentResponse findById(@PathVariable("id") Long id) {
        return studentMapper.toDetailResponse(studentService.findByIdWithEnrollments(id));
    }

    /**
     * Lookup by the business key rather than the surrogate one.
     *
     * <p>A separate path segment - {@code /by-number/123456} - rather than
     * overloading {@code /{id}} and guessing from the shape of the value.
     * Guessing works until a student number is all digits, which this one is.
     */
    @GetMapping("/by-number/{studentNumber}")
    public StudentResponse findByStudentNumber(@PathVariable("studentNumber") String studentNumber) {
        return studentMapper.toSummaryResponse(studentService.findByStudentNumber(studentNumber));
    }

    @GetMapping("/{id}/enrollments")
    public List<EnrollmentResponse> findEnrollments(@PathVariable("id") Long id) {
        return enrollmentMapper.toResponseList(enrollmentService.findByStudent(id));
    }

    // ==================================================================
    // PATCH /api/students/{id}
    // ==================================================================

    /** Partial update. See {@link UpdateStudentRequest} for PATCH vs PUT. */
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StudentResponse update(@PathVariable("id") Long id,
                                  @Valid @RequestBody UpdateStudentRequest request) {
        Student updated = studentService.update(
                id, request.getFirstName(), request.getLastName(), request.getEmail());
        return studentMapper.toSummaryResponse(updated);
    }

    // ==================================================================
    // State-changing actions
    // ==================================================================

    /**
     * Suspends a student.
     *
     * <h3>Actions that are not CRUD</h3>
     * Strict REST says "everything is a resource, manipulated with the standard
     * verbs", which would make this
     * {@code PATCH /students/42} with {@code {"status": "SUSPENDED"}}.
     *
     * <p>In practice most teams expose a sub-resource per action, as here.
     * The reasons are pragmatic and good: the intent is explicit and greppable,
     * each action can have its own permissions and audit entry, and the client
     * cannot construct an illegal transition by writing an arbitrary status. The
     * cost is a slightly less "pure" API, which nobody has ever regretted.
     */
    @PostMapping("/{id}/suspension")
    public StudentResponse suspend(@PathVariable("id") Long id) {
        return studentMapper.toSummaryResponse(studentService.suspend(id));
    }

    @DeleteMapping("/{id}/suspension")
    public StudentResponse reinstate(@PathVariable("id") Long id) {
        return studentMapper.toSummaryResponse(studentService.reinstate(id));
    }

    // ==================================================================
    // DELETE /api/students/{id}
    // ==================================================================

    /**
     * Deletes a student and, by cascade, their enrollments.
     *
     * <p>{@code 204 No Content} is the conventional answer to a successful
     * DELETE: the operation worked and there is nothing meaningful to return.
     * Returning 200 with an empty body, or with the deleted object, are both
     * things you will see in the wild - 204 is the one to prefer.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        studentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
