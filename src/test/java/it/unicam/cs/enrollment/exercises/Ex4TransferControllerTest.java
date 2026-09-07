package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.domain.model.Enrollment;
import it.unicam.cs.enrollment.exception.BusinessRuleViolationException;
import it.unicam.cs.enrollment.exception.ResourceNotFoundException;
import it.unicam.cs.enrollment.web.dto.EnrollmentResponse;
import it.unicam.cs.enrollment.web.mapper.EnrollmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Specification for Exercise 4.
 *
 * <p>Note what is <em>not</em> tested here: none of the business rules. Those
 * belong to Exercise 3 and are tested there. A controller test should only be
 * able to fail for controller reasons - wrong status code, wrong delegation,
 * swallowed exception. If you find yourself wanting to test a rule here, the
 * rule is in the wrong layer.
 *
 * <p>A plain unit test with the controller constructed by hand, rather than
 * {@code @WebMvcTest} and {@code MockMvc}. Both are legitimate: MockMvc goes
 * through the real dispatcher and would also check the URL mapping and the JSON,
 * at the cost of a Spring context per test class. For an exercise about
 * delegation and a status code, calling the method is faster and the failure
 * messages are clearer. {@code CourseControllerTest} is the MockMvc example.
 */
@Tag("exercise")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Exercise 4: the transfer endpoint")
class Ex4TransferControllerTest {

    @Mock private Ex3TransferService transferService;
    @Mock private EnrollmentMapper enrollmentMapper;
    @Mock private Enrollment enrollment;

    private Ex4TransferController controller;
    private EnrollmentResponse mapped;

    @BeforeEach
    void setUp() {
        controller = new Ex4TransferController(transferService, enrollmentMapper);
        mapped = new EnrollmentResponse(1L, 1L, "123456", "Mario Rossi",
                10L, "CS201", "Algorithms", 9, "ACTIVE",
                Instant.parse("2026-03-01T10:00:00Z"), null, null, false, null);
        when(transferService.transfer(any(), any(), any())).thenReturn(enrollment);
        when(enrollmentMapper.toResponse(any(Enrollment.class))).thenReturn(mapped);
    }

    private Ex4TransferController.TransferRequest aRequest() {
        return new Ex4TransferController.TransferRequest(1L, 10L, 20L);
    }

    @Test
    @DisplayName("returns 200 on success")
    void returnsOk() {
        ResponseEntity<EnrollmentResponse> response = controller.transfer(aRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("returns the mapped DTO as the body, never the entity")
    void returnsMappedDto() {
        ResponseEntity<EnrollmentResponse> response = controller.transfer(aRequest());

        assertThat(response.getBody())
                .as("the body must be the DTO from the mapper, not the JPA entity")
                .isSameAs(mapped);
    }

    @Test
    @DisplayName("passes the request fields straight through to the service")
    void delegatesToService() {
        controller.transfer(new Ex4TransferController.TransferRequest(7L, 70L, 80L));

        verify(transferService).transfer(7L, 70L, 80L);
    }

    @Test
    @DisplayName("does not catch ResourceNotFoundException - the handler turns it into 404")
    void letsNotFoundPropagate() {
        when(transferService.transfer(any(), any(), any()))
                .thenThrow(ResourceNotFoundException.of("Course", 20L));

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> controller.transfer(aRequest()));
    }

    @Test
    @DisplayName("does not catch BusinessRuleViolationException - the handler turns it into 409")
    void letsRuleViolationPropagate() {
        when(transferService.transfer(any(), any(), any()))
                .thenThrow(BusinessRuleViolationException.courseFull("CS201", 30));

        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> controller.transfer(aRequest()));
    }
}
