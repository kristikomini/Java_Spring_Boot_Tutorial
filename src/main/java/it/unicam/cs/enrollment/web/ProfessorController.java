package it.unicam.cs.enrollment.web;

import it.unicam.cs.enrollment.service.ProfessorService;
import it.unicam.cs.enrollment.web.dto.ProfessorResponse;
import it.unicam.cs.enrollment.web.mapper.ProfessorMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Teaching staff. Read-only, and short enough to read in one go.
 *
 * <p>Present partly for completeness - a course has a professor and the client
 * needs to render one - and partly as the plainest controller in the codebase:
 * two methods, no status-code decisions, no request bodies. Not every class has
 * to be interesting, and a codebase where they all are is usually a codebase
 * that is trying too hard.
 *
 * <p>Note that the collection is NOT paginated, which contradicts the rule
 * {@code PageRequest} states so firmly. It is a deliberate exception: a
 * university department has tens of professors, not tens of thousands, and the
 * bound is structural rather than hopeful. State the reason, as here, or apply
 * the rule.
 */
@RestController
@RequestMapping(path = {"/api/professors", "/api/v1/professors"},
        produces = MediaType.APPLICATION_JSON_VALUE)
public class ProfessorController {

    private final ProfessorService professorService;
    private final ProfessorMapper professorMapper;

    public ProfessorController(ProfessorService professorService, ProfessorMapper professorMapper) {
        this.professorService = professorService;
        this.professorMapper = professorMapper;
    }

    @GetMapping
    public List<ProfessorResponse> findAll() {
        return professorMapper.toResponseList(professorService.findAll());
    }

    @GetMapping("/{id}")
    public ProfessorResponse findById(@PathVariable("id") Long id) {
        return professorMapper.toResponse(professorService.findById(id));
    }
}
