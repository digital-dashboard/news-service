package com.j11a.argus.web.error;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException.Reference;
import tools.jackson.databind.exc.MismatchedInputException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// S2638 false positive: Spring 7 declares these handler returns @Nullable as a type-use annotation, which Sonar
// does not read, so it treats the overrides' matching @Nullable returns as loosening a non-null contract.
@SuppressWarnings("java:S2638")
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String UNEXPECTED_DETAIL = "An unexpected error occurred.";
    private static final String INVALID_VALUE = "invalid value";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = Problems.of(ex.code(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.code().status()).body(problem);
    }

    /** Spring Security's ExceptionTranslationFilter owns these; the catch-all below would turn them into a 500. */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    void rethrowSecurityException(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        LOG.error("Unhandled exception", ex);
        return Problems.response(ErrorCode.INTERNAL_ERROR, UNEXPECTED_DETAIL);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        Object problemBody = body == null && ex instanceof ErrorResponse errorResponse ? errorResponse.getBody() : body;
        if (problemBody instanceof ProblemDetail problem && !Problems.hasCode(problem)) {
            Problems.applyContract(problem, ErrorCode.forStatus(statusCode));
        }
        return super.handleExceptionInternal(ex, problemBody, headers, statusCode, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldProblem(
                        error instanceof FieldError field ? field.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .toList();
        return validationFailed(ex, headers, status, request, errors);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldProblem(
                                String.valueOf(result.getMethodParameter().getParameterName()),
                                error.getDefaultMessage())))
                .toList();
        return validationFailed(ex, headers, status, request, errors);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName() : String.valueOf(ex.getPropertyName());
        return validationFailed(ex, headers, status, request, List.of(new FieldProblem(field, INVALID_VALUE)));
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        FieldProblem error = new FieldProblem(ex.getParameterName(), "parameter is required");
        return validationFailed(ex, headers, status, request, List.of(error));
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().stream()
                    .map(Reference::getPropertyName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            if (!field.isEmpty()) {
                return validationFailed(ex, headers, status, request, List.of(new FieldProblem(field, INVALID_VALUE)));
            }
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    private @Nullable ResponseEntity<Object> validationFailed(
            Exception ex, HttpHeaders headers, HttpStatusCode status, WebRequest request, List<FieldProblem> errors) {
        ProblemDetail problem = Problems.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.title());
        problem.setProperty(Problems.ERRORS_PROPERTY, errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
