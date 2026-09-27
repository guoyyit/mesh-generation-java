package com.github.guoyyit.meshgenerationjava.task;

/** Raised inside a task when its deadline expires. */
public class TaskTimeoutException extends RuntimeException {
    public TaskTimeoutException(String message) {
        super(message);
    }
}
