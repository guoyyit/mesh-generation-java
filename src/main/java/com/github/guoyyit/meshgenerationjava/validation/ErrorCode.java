package com.github.guoyyit.meshgenerationjava.validation;

/** Machine-readable failure classification. */
public enum ErrorCode {
    DUPLICATE_POINT,
    SELF_INTERSECTING_BOUNDARY,
    DEGENERATE_GEOMETRY,
    HOLE_OUTSIDE_BOUNDARY,
    HOLE_INTERSECTS_BOUNDARY,
    OVERLAPPING_HOLES,
    INVALID_PARAMETER,
    QUALITY_VIOLATION,
    TASK_NOT_FOUND,
    TASK_NOT_COMPLETED,
    TASK_CANCELLED,
    TASK_TIMEOUT,
    GENERATION_FAILED
}
