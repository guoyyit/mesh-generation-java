package com.github.guoyyit.meshgenerationjava.task;

/** Lifecycle of a mesh generation task. */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    /** Was running/pending when the process stopped; distinguishable from a committed result. */
    INTERRUPTED
}
