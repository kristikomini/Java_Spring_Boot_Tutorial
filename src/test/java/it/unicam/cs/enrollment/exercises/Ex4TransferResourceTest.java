package it.unicam.cs.enrollment.exercises;

import it.unicam.cs.enrollment.api.dto.response.EnrollmentResponse;
import it.unicam.cs.enrollment.api.mapper.EnrollmentMapper;
import it.unicam.cs.enrollment.domain.model.Enrollment;
import it.unicam.cs.enrollment.exception.BusinessRuleViolationException;
import it.unicam.cs.enrollment.exception.ResourceNotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Specification for Exercise 4.
 *
 * <p>Note what is <em>not</em> tested here: none of the business rules. Those
 * belong to Exercise 3 and are tested there. A resource test should only be
 * able to fail for resource reasons - wrong status code, wrong delegation,
 * swallowed exception. If you find yourself wanting to test a rule here, the
 * rule is in the wrong layer.
 */
@Tag("exercise")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Exercise 4: the transfer endpoint")
class Ex4TransferResourceTest {

    @Mock private Ex3TransferService transferService;
    @Mock private EnrollmentMapper enrollmentMapper;
    @Mock private Enrollment enrollment;

    private Ex4TransferResource resource;
    private EnrollmentResponse mapped;

    @BeforeEach
    void setUp() {
        resource = new Ex4TransferResource(transferService, enrollmentMapper);
        mapped = new EnrollmentResponse();
        when(transferService.transfer(any(), any(), any())).thenReturn(enrollment);
        when(enrollmentMapper.toResponse(any(Enrollment.class))).thenReturn(mapped);
    }

    private Ex4TransferResource.TransferRequest aRequest() {
        return new Ex4TransferResource.TransferRequest(1L, 10L, 20L);
    }

    @Test
    @DisplayName("returns 200 on success")
    void returnsOk() {
        Response response = resource.transfer(aRequest());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("returns the mapped DTO as the body, never the entity")
    void returnsMappedDto() {
        Response response = resource.transfer(aRequest());

        assertThat(response.getEntity())
                .as("the body must be the DTO from the mapper, not the JPA entity")
                .isSameAs(mapped);
    }

    @Test
    @DisplayName("passes the request fields straight through to the service")
    void delegatesToService() {
        resource.transfer(new Ex4TransferResource.TransferRequest(7L, 70L, 80L));

        verify(transferService).transfer(7L, 70L, 80L);
    }

    @Test
    @DisplayName("does not catch ResourceNotFoundException - the mapper turns it into 404")
    void letsNotFoundPropagate() {
        when(transferService.transfer(any(), any(), any()))
                .thenThrow(ResourceNotFoundException.of("Course", 20L));

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> resource.transfer(aRequest()));
    }

    @Test
    @DisplayName("does not catch BusinessRuleViolationException - the mapper turns it into 409")
    void letsRuleViolationPropagate() {
        when(transferService.transfer(any(), any(), any()))
                .thenThrow(BusinessRuleViolationException.courseFull("CS201", 30));

        assertThatExceptionOfType(BusinessRuleViolationException.class)
                .isThrownBy(() -> resource.transfer(aRequest()));
    }
}
