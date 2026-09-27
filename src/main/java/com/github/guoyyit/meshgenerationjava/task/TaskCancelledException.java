package com.github.guoyyit.meshgenerationjava.task;

/** Raised inside a task when cancellation is requested. */
public class TaskCancelledException extends RuntimeException {
    public TaskCancelledException(String message) {
        super(message);
    }
}
