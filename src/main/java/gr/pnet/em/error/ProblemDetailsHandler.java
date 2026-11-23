package gr.pnet.em.error;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import gr.pnet.em.support.Masking;
import gr.pnet.em.web.RequestOutcome;
import jakarta.validation.ConstraintViolationException;

/**
 * Every error, on the TEP and the 3GPP routes alike, is an RFC 9457 problem ({@code application/problem+json}):
 * what TEP specifies, and a valid 3GPP ProblemDetails. The base class covers Spring MVC's own errors
 * (malformed body, 405, 415, unknown path).
 */
@RestControllerAdvice
public class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException e) {
        return problem(e, HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ProblemDetail handleConflict(ResourceConflictException e) {
        return problem(e, HttpStatus.CONFLICT, e.getMessage());
    }

    // A concurrent create got past the existence check first
    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail handleDuplicateKey(DuplicateKeyException e) {
        return problem(e, HttpStatus.CONFLICT, "Resource already exists");
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException e) {
        ProblemDetail problem = problem(e, HttpStatus.BAD_REQUEST, e.getMessage());
        if (e.getParam() != null) {
            problem.setProperty("invalidParams", List.of(Map.of("param", e.getParam(), "reason", e.getMessage())));
        }
        return problem;
    }

    // Path and query constraints of the generated @Validated API interfaces
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> lastNode(v.getPropertyPath().toString()) + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(e, HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(NotImplementedException.class)
    public ProblemDetail handleNotImplemented(NotImplementedException e) {
        return problem(e, HttpStatus.NOT_IMPLEMENTED, e.getMessage());
    }

    // An Open5GS function failed: a line with the cause, but no stack trace, which says nothing more here
    @ExceptionHandler(UpstreamException.class)
    public ProblemDetail handleUpstream(UpstreamException e) {
        Throwable cause = e.getCause();
        log.warn("Open5GS call failed: {} cause={}: {}", e.getMessage(),
                cause == null ? null : cause.getClass().getSimpleName(),
                cause == null ? null : Masking.maskAll(cause.getMessage()));
        return problem(e, HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    // A bug: the stack trace, and a detail that gives nothing away
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(e, HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");
    }

    // Spring MVC's own errors (malformed body, 405, 415, unknown path, validation): recorded for the access log
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        RequestOutcome.error(ex);
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /** A problem, with the exception behind it recorded for the access log. */
    private static ProblemDetail problem(Exception e, HttpStatus status, String detail) {
        RequestOutcome.error(e);
        return ProblemDetail.forStatusAndDetail(status, detail);
    }

    // The default detail is only "Invalid request content."; name the fields instead
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setDetail(ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; ")));
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setDetail(ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName() + ": "
                                + error.getDefaultMessage()))
                .sorted()
                .collect(Collectors.joining("; ")));
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    // "listSubscribers.limit" → "limit"
    private static String lastNode(String propertyPath) {
        return propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
    }
}
