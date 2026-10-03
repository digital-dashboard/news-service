package com.j11a.argus.web.error;

import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException.Reference;
import tools.jackson.databind.exc.MismatchedInputException;

// S2638 false positive: Spring 7 declares these handler returns @Nullable as a type-use annotation, which Sonar
// does not read, so it treats the overrides' matching @Nullable returns as loosening a non-null contract.
@SuppressWarnings("java:S2638")
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String UNEXPECTED_DETAIL = "An unexpected error occurred.";
    private static final String INVALID_VALUE = "invalid value";
    private static final int MAX_PATH_LENGTH = 200;
    // FeedService logs these itself, with the cause fields, so the generic line would be a duplicate.
    private static final Set<String> LOGGED_BY_THE_CALLER =
            Set.of(ErrorCode.FEED_INVALID.name(), ErrorCode.FEED_URL_CONFLICT.name());

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        logApiException(ex, request);
        ProblemDetail problem = Problems.of(ex.code(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.code().status()).body(problem);
    }

    @ExceptionHandler(InvocationRejectedException.class)
    ResponseEntity<ProblemDetail> handleInvocationRejected(InvocationRejectedException ex,
            HttpServletRequest request) {
        logRejected(ErrorCode.POLL_IN_PROGRESS.name(), ErrorCode.POLL_IN_PROGRESS.status(), request);
        return Problems.response(ErrorCode.POLL_IN_PROGRESS, "A poll is already in progress.");
    }

    /** Spring Security's ExceptionTranslationFilter owns these; the catch-all below would turn them into a 500. */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    void rethrowSecurityException(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        logUnhandled(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.status(), request, ex);
        return Problems.response(ErrorCode.INTERNAL_ERROR, UNEXPECTED_DETAIL);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        Object problemBody = body == null && ex instanceof ErrorResponse errorResponse ? errorResponse.getBody() : body;
        if (problemBody instanceof ProblemDetail problem && !Problems.hasCode(problem)) {
            Problems.applyContract(problem, ErrorCode.forStatus(statusCode));
        }
        if (request instanceof ServletWebRequest servletRequest) {
            logFramework(codeOf(problemBody, statusCode), statusCode, servletRequest.getRequest(), ex);
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

    private static String codeOf(@Nullable Object body, HttpStatusCode statusCode) {
        String code = body instanceof ProblemDetail problem ? Problems.codeOf(problem) : null;
        return code != null ? code : ErrorCode.forStatus(statusCode).name();
    }

    /** A 5xx from the framework itself is unexpected; anything else is a rejected request. */
    private static void logFramework(String code, HttpStatusCode status, HttpServletRequest request, Exception cause) {
        if (status.is5xxServerError()) {
            logUnhandled(code, status, request, cause);
        } else {
            logRejected(code, status, request);
        }
    }

    /** A handled 5xx (for example a 503 while shutting down) is a WARN without a stack trace. */
    private static void logApiException(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.code();
        if (!code.status().is5xxServerError()) {
            logRejected(code.name(), code.status(), request);
            return;
        }
        String method = request.getMethod();
        String path = cappedPath(request);
        emit(Level.WARN, "Request failed: " + code.name() + " " + code.status().value() + " " + method + " " + path,
                code.name(), code.status(), method, path, null);
    }

    /** The only ERROR with a stack trace: nothing else has logged this exception. */
    private static void logUnhandled(String code, HttpStatusCode status, HttpServletRequest request, Exception cause) {
        String method = request.getMethod();
        String path = cappedPath(request);
        emit(Level.ERROR, "Unhandled exception: " + method + " " + path, code, status, method, path, cause);
    }

    private static void logRejected(String code, HttpStatusCode status, HttpServletRequest request) {
        String method = request.getMethod();
        Level level = levelFor(code, status, request.getRequestURI());
        String path = cappedPath(request);
        emit(level, "Request rejected: " + code + " (" + status.value() + ") for " + method + " " + path,
                code, status, method, path, null);
    }

    private static void emit(Level level, String message, String code, HttpStatusCode status, String method,
            String path, @Nullable Exception cause) {
        log.atLevel(level)
                .setMessage(message)
                .addKeyValue(LogKeys.CODE, code)
                .addKeyValue(LogKeys.STATUS, status.value())
                .addKeyValue(LogKeys.METHOD, method)
                .addKeyValue(LogKeys.PATH, path)
                .setCause(cause)
                .log();
    }

    // Only the path is logged, never the query string, and a scanner's long URL is cut.
    private static String cappedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) : path;
    }

    /**
     * A missing admin key under /news/v2 is a WARN; a 404/405 outside /news/v2 (scanner noise) is DEBUG; every other
     * client error is an INFO.
     */
    private static Level levelFor(String code, HttpStatusCode status, String path) {
        if (LOGGED_BY_THE_CALLER.contains(code)) {
            return Level.DEBUG;
        }
        boolean outsideApi = !path.startsWith(ApiPaths.BASE);
        if (ErrorCode.ADMIN_KEY_REQUIRED.name().equals(code)) {
            return outsideApi ? Level.DEBUG : Level.WARN;
        }
        boolean notFoundOrWrongMethod = status.value() == HttpStatus.NOT_FOUND.value()
                || status.value() == HttpStatus.METHOD_NOT_ALLOWED.value();
        return notFoundOrWrongMethod && outsideApi ? Level.DEBUG : Level.INFO;
    }

    private @Nullable ResponseEntity<Object> validationFailed(
            Exception ex, HttpHeaders headers, HttpStatusCode status, WebRequest request, List<FieldProblem> errors) {
        ProblemDetail problem = Problems.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.title());
        problem.setProperty(Problems.ERRORS_PROPERTY, errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
