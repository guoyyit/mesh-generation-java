package com.github.guoyyit.meshgenerationjava.task;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation + deadline checked by long-running generation loops. */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final long deadlineMillis;

    public CancellationToken(long deadlineMillis) {
        this.deadlineMillis = deadlineMillis;
    }

    public static CancellationToken none() {
        return new CancellationToken(Long.MAX_VALUE);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Throws TaskCancelledException or TaskTimeoutException when the task should stop. */
    public void checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException("task was cancelled");
        }
        if (System.currentTimeMillis() > deadlineMillis) {
            throw new TaskTimeoutException("task exceeded its deadline");
        }
    }
}
