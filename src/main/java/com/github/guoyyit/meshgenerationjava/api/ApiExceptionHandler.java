package com.github.guoyyit.meshgenerationjava.api;

import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps classified failures to HTTP responses; bad input never crashes the service. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MeshValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MeshValidationException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TASK_NOT_COMPLETED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCode.GENERATION_FAILED.name(),
                        "internal error: " + ex.getMessage()));
    }

    public record ErrorResponse(String errorCode, String message) {
    }
}
