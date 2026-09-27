package com.github.guoyyit.meshgenerationjava.web;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常出口：任何异常输入都得到可区分的错误响应，服务不崩溃。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MeshException.class)
    public ResponseEntity<ErrorResponse> handleMesh(MeshException e) {
        MeshErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus())
                .body(new ErrorResponse(code.getCode(), e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(MeshErrorCode.MALFORMED_REQUEST.getCode(),
                        "request body is malformed: " + e.getMostSpecificCause().getMessage(),
                        Map.of()));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMedia(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(MeshErrorCode.MALFORMED_REQUEST.getCode(),
                        "unsupported content type: " + e.getContentType(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(MeshErrorCode.INTERNAL_ERROR.getCode(),
                        e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of()));
    }
}
