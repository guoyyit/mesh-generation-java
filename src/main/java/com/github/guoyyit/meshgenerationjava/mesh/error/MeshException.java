package com.github.guoyyit.meshgenerationjava.mesh.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网格服务唯一受检外的业务异常：携带可区分的错误码与结构化明细。
 */
public class MeshException extends RuntimeException {

    private final MeshErrorCode errorCode;
    private final Map<String, Object> details;

    public MeshException(MeshErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public MeshException(MeshErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, cause);
    }

    public MeshException(MeshErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    public MeshException(MeshErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public MeshErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
