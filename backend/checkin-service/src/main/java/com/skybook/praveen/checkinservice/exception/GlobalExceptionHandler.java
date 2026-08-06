package com.skybook.praveen.checkinservice.exception;

import com.skybook.praveen.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({
            CheckInNotFoundException.class,
            BoardingPassNotFoundException.class,
            FlightNotFoundForCheckInException.class
    })
    public ResponseEntity<ErrorResponse> handleNotFound(
            RuntimeException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    // 422: the token was well-formed, verification itself failed (design doc section 6/7).
    @ExceptionHandler(BoardingPassVerificationException.class)
    public ResponseEntity<ErrorResponse> handleVerificationFailed(
            BoardingPassVerificationException exception, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), request);
    }

    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleSeatUnavailable(
            SeatUnavailableException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler({
            FlightServiceUnavailableException.class,
            InventoryServiceUnavailableException.class
    })
    public ResponseEntity<ErrorResponse> handleUpstreamUnavailable(
            RuntimeException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_GATEWAY, exception.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException exception, HttpServletRequest request) {
        return build(
                HttpStatus.CONFLICT,
                "This check-in was modified by another request. Please reload and try again.",
                request
        );
    }

    @ExceptionHandler(com.skybook.praveen.security.ResourceConcealedException.class)
    public ResponseEntity<ErrorResponse> handleResourceConcealed(
            com.skybook.praveen.security.ResourceConcealedException exception, HttpServletRequest request) {
        // GUEST_CHECKIN_MODULE.md D8: outside a guest's one booking, nothing
        // exists - 404, with the same generic body a truly absent id gets.
        return build(HttpStatus.NOT_FOUND, "Check-in not found", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
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
