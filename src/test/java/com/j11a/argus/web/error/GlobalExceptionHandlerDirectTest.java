package com.j11a.argus.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.PropertyChangeEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/** Spring exceptions that no controller in the slice tests raises, handed to the advice directly. */
class GlobalExceptionHandlerDirectTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final WebRequest request = new ServletWebRequest(new MockHttpServletRequest());

    static void endpoint(String body) {
        // Only its signature is used, to build a MethodParameter.
    }

    @Test
    void anExceptionThatIsNotAnErrorResponseKeepsItsEmptyBody() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalStateException("not an error response"), null, new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR, request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void aClassLevelViolationIsReportedUnderTheObjectName() throws Exception {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "passwordChange");
        binding.reject("mismatch", "passwords differ");
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerDirectTest.class.getDeclaredMethod("endpoint", String.class), 0);

        ResponseEntity<Object> response = handler.handleException(
                new MethodArgumentNotValidException(parameter, binding), request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problem -> {
            assertThat(problem.getProperties())
                    .containsEntry("code", "VALIDATION_FAILED")
                    .containsEntry("errors", List.of(new FieldProblem("passwordChange", "passwords differ")));
        });
    }

    @Test
    void aPlainTypeMismatchNamesThePropertyItWasBoundTo() throws Exception {
        TypeMismatchException mismatch = new TypeMismatchException(
                new PropertyChangeEvent(new Object(), "age", null, "abc"), Integer.class);

        ResponseEntity<Object> response = handler.handleException(mismatch, request);

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, problem ->
                assertThat(problem.getProperties())
                        .containsEntry("errors", List.of(new FieldProblem("age", "invalid value"))));
    }
}
