package com.github.guoyyit.meshgenerationjava.validation;

/** Raised for invalid region definitions, invalid parameters or unmet quality requirements. */
public class MeshValidationException extends RuntimeException {

    private final ErrorCode code;

    public MeshValidationException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
