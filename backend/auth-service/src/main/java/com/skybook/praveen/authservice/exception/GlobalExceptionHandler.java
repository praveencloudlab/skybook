package com.skybook.praveen.authservice.exception;

import com.skybook.praveen.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * Auth-service error surface (SECURITY_HARDENING_MODULE.md §6). Matches the
 * fleet house pattern (booking/flight GlobalExceptionHandler + the shared
 * {@link ErrorResponse}). Before this, auth-service had no advice and threw
 * bare {@code RuntimeException}s that surfaced as 500s.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Duplicate email (pre-check or the concurrent-insert race) -> generic 409. */
    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyRegistered(
            EmailAlreadyRegisteredException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    /** Bad credentials - identical generic 401 for unknown-user and wrong-password. */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, exception.getMessage(), request);
    }

    /** A saved traveller the caller doesn't own (or doesn't exist) - 404. */
    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            jakarta.persistence.EntityNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    /** Wrong current password on a signed-in change - clear 400. */
    @ExceptionHandler(IncorrectCurrentPasswordException.class)
    public ResponseEntity<ErrorResponse> handleIncorrectCurrentPassword(
            IncorrectCurrentPasswordException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    /** Unknown/spent/expired reset token - generic 400, never says which. */
    @ExceptionHandler(InvalidResetTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidResetToken(
            InvalidResetTokenException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    /**
     * Correct password, unverified email - 403. Distinct from the 401 above on
     * purpose: the client routes this one to the code-entry step, and only the
     * account's owner (who just proved the password) ever sees it.
     */
    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ErrorResponse> handleEmailNotVerified(
            EmailNotVerifiedException exception, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, exception.getMessage(), request);
    }

    /** Unknown account, missing/expired code, or wrong digits - one generic 400. */
    @ExceptionHandler(InvalidVerificationCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationCode(
            InvalidVerificationCodeException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    /** Guess cap exhausted on one code - 429, the fix is a fresh code. */
    @ExceptionHandler(TooManyVerificationAttemptsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyVerificationAttempts(
            TooManyVerificationAttemptsException exception, HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), request);
    }

    /** Bean-validation failures on @Valid request bodies -> 400 with field messages. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException exception, HttpServletRequest request) {

        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {

        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(errorResponse);
    }
}
