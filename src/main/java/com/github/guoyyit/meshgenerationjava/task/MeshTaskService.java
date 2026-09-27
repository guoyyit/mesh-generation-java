package com.github.guoyyit.meshgenerationjava.task;

import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.generator.GenerationResult;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerationOptions;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerator;
import com.github.guoyyit.meshgenerationjava.geometry.Region;
import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs generation tasks concurrently with isolation, cancellation, timeouts
 * and restart recovery. A result is committed (atomically, result file first
 * then COMPLETED status) only when generation fully succeeded, so cancelled,
 * timed-out or crashed tasks never leave a half-baked readable result.
 */
public class MeshTaskService {

    private final TaskStore store;
    private final ExecutorService executor;
    private final long defaultTimeoutMillis;
    private final MeshGenerator generator = new MeshGenerator();
    private final Map<String, CancellationToken> tokens = new ConcurrentHashMap<>();
    private volatile boolean accepting = true;

    public MeshTaskService(TaskStore store, int workerThreads, long defaultTimeoutMillis) {
        this.store = store;
        this.executor = Executors.newFixedThreadPool(workerThreads);
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    public String submit(Region region, MeshGenerationOptions options) {
        if (!accepting) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER, "service is shut down");
        }
        MeshTask task = new MeshTask();
        task.setId(UUID.randomUUID().toString());
        task.setStatus(TaskStatus.PENDING);
        task.setRegion(region);
        task.setOptions(options);
        long now = System.currentTimeMillis();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        store.saveTask(task);

        long timeout = options != null && options.getTimeoutMillis() > 0
                ? options.getTimeoutMillis() : defaultTimeoutMillis;
        CancellationToken token = new CancellationToken(now + timeout);
        tokens.put(task.getId(), token);
        // Cancellation is cooperative via the token, so no Future handle is needed.
        executor.submit(() -> runTask(task.getId(), token));
        return task.getId();
    }

    private void runTask(String id, CancellationToken token) {
        MeshTask task = store.loadTask(id).orElseThrow(
                () -> new IllegalStateException("task vanished: " + id));
        try {
            token.checkCancelled();
            task.setStatus(TaskStatus.RUNNING);
            touch(task);
            store.saveTask(task);

            GenerationResult result = generator.generate(task.getRegion(), task.getOptions(), token);

            token.checkCancelled(); // a cancel/timeout after this point cannot be honoured
            store.saveResult(id, result.mesh()); // commit result first...
            task.setStatus(TaskStatus.COMPLETED);   // ...then flip status
            touch(task);
            store.saveTask(task);
        } catch (TaskCancelledException e) {
            fail(task, TaskStatus.CANCELLED, ErrorCode.TASK_CANCELLED, e.getMessage());
        } catch (TaskTimeoutException e) {
            fail(task, TaskStatus.FAILED, ErrorCode.TASK_TIMEOUT, e.getMessage());
        } catch (MeshValidationException e) {
            fail(task, TaskStatus.FAILED, e.getCode(), e.getMessage());
        } catch (Exception e) {
            fail(task, TaskStatus.FAILED, ErrorCode.GENERATION_FAILED, String.valueOf(e.getMessage()));
        } finally {
            tokens.remove(id);
        }
    }

    private void fail(MeshTask task, TaskStatus status, ErrorCode code, String message) {
        task.setStatus(status);
        task.setErrorCode(code.name());
        task.setError(message);
        touch(task);
        store.saveTask(task);
    }

    private static void touch(MeshTask task) {
        task.setUpdatedAt(System.currentTimeMillis());
    }

    public MeshTask getTask(String id) {
        return store.loadTask(id).orElseThrow(
                () -> new MeshValidationException(ErrorCode.TASK_NOT_FOUND, "no such task: " + id));
    }

    /** Returns the committed mesh; throws MeshValidationException(TASK_NOT_COMPLETED) otherwise. */
    public Mesh getResult(String id) {
        MeshTask task = getTask(id);
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new MeshValidationException(ErrorCode.TASK_NOT_COMPLETED,
                    "task " + id + " is " + task.getStatus() + ", result not available");
        }
        return store.loadResult(id).orElseThrow(() -> new MeshValidationException(
                ErrorCode.GENERATION_FAILED, "committed result file missing for task " + id));
    }

    /** Cooperative cancel; returns false when the task is already in a terminal state. */
    public boolean cancel(String id) {
        MeshTask task = getTask(id);
        if (isTerminal(task.getStatus())) {
            return false;
        }
        CancellationToken token = tokens.get(id);
        if (token != null) {
            token.cancel();
        } else {
            // Queued but not started (or this instance does not own it): cancel directly.
            task.setStatus(TaskStatus.CANCELLED);
            task.setErrorCode(ErrorCode.TASK_CANCELLED.name());
            task.setError("cancelled before start");
            touch(task);
            store.saveTask(task);
        }
        return true;
    }

    public List<MeshTask> listTasks() {
        return store.listTasks();
    }

    /**
     * Recovery after a process restart: tasks that were PENDING/RUNNING when the
     * previous process stopped are marked INTERRUPTED (never mistaken for a
     * committed result). Returns how many were marked. Completed tasks and their
     * results stay untouched and readable.
     */
    public int recover() {
        int marked = 0;
        for (MeshTask task : store.listTasks()) {
            if (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.RUNNING) {
                task.setStatus(TaskStatus.INTERRUPTED);
                task.setErrorCode(ErrorCode.GENERATION_FAILED.name());
                task.setError("interrupted by process shutdown; resubmit to rerun from scratch");
                touch(task);
                store.saveTask(task);
                marked++;
            }
        }
        return marked;
    }

    /**
     * Re-queues an INTERRUPTED/FAILED/CANCELLED task from its stored request.
     * Reruns from scratch with the same seed, so no refinement step is
     * duplicated or skipped; returns the new task id.
     */
    public String resubmit(String taskId) {
        MeshTask old = getTask(taskId);
        if (old.getStatus() != TaskStatus.INTERRUPTED
                && old.getStatus() != TaskStatus.FAILED
                && old.getStatus() != TaskStatus.CANCELLED) {
            throw new MeshValidationException(ErrorCode.INVALID_PARAMETER,
                    "cannot resubmit task in status " + old.getStatus());
        }
        return submit(old.getRegion(), old.getOptions());
    }

    public void shutdown() {
        accepting = false;
        for (CancellationToken token : tokens.values()) {
            token.cancel();
        }
        executor.shutdownNow();
    }

    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED || status == TaskStatus.INTERRUPTED;
    }
}
