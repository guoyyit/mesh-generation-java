package com.github.guoyyit.meshgenerationjava.service;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationRequest;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationResult;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.MeshGenerationPipeline;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.PipelineHook;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityChecker;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityReport;
import com.github.guoyyit.meshgenerationjava.mesh.refine.MeshRefiner;
import com.github.guoyyit.meshgenerationjava.mesh.refine.MeshCoarsener;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 并发生成任务管理器：
 * <ul>
 *   <li>任务隔离：每个任务独立请求/网格对象与磁盘目录，线程池执行；</li>
 *   <li>取消/超时：中断工作线程，结果文件绝不写入半成品；</li>
 *   <li>恢复：启动扫描，已提交结果与未完成任务严格区分，按检查点续跑；</li>
 *   <li>细分/粗化：仅允许在 COMPLETED 任务上同步执行，按任务加锁并原子重写结果。</li>
 * </ul>
 */
public class MeshTaskManager implements AutoCloseable {

    private static final class Handle {
        final String id;
        volatile TaskRecord record;
        volatile Future<?> future;
        volatile ScheduledFuture<?> timeoutFuture;
        volatile boolean cancelRequested;
        volatile boolean timeoutRequested;

        Handle(String id, TaskRecord record) {
            this.id = id;
            this.record = record;
        }
    }

    private final TaskStore store;
    private final MeshProperties properties;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Handle> handles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> taskLocks = new ConcurrentHashMap<>();

    /** Spring 使用：默认从配置目录恢复。 */
    public MeshTaskManager(MeshProperties properties) {
        this(new TaskStore(Path.of(properties.getStoreDir())), properties, true);
    }

    /** 测试使用：指定 store，可关闭启动恢复。 */
    public MeshTaskManager(TaskStore store, MeshProperties properties, boolean recoverOnStartup) {
        this.store = store;
        this.properties = properties;
        int threads = Math.max(1, properties.getWorkerThreads());
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "mesh-worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mesh-timeout");
            t.setDaemon(true);
            return t;
        });
        if (recoverOnStartup) {
            recover();
        }
    }

    // ---------------------------------------------------------------- 提交

    public String submit(GenerationRequest request) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        TaskRecord record = new TaskRecord(id, TaskStatus.PENDING, request, -1,
                null, null, now, now);
        Handle handle = new Handle(id, record);
        handles.put(id, handle);
        store.saveTask(record);
        startExecution(handle, null);
        return id;
    }

    private void startExecution(Handle handle, Checkpoint checkpoint) {
        Future<?> future = workers.submit(() -> execute(handle, checkpoint));
        handle.future = future;
        Duration timeout = properties.getTaskTimeout();
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            handle.timeoutFuture = scheduler.schedule(() -> fireTimeout(handle),
                    timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void fireTimeout(Handle handle) {
        synchronized (handle) {
            if (handle.record.status().isTerminal()) {
                return;
            }
            handle.timeoutRequested = true;
            Future<?> f = handle.future;
            if (f != null) {
                f.cancel(true);
            }
            if (handle.record.status() == TaskStatus.PENDING) {
                update(handle, TaskStatus.TIMEOUT, handle.record.currentStep(),
                        MeshErrorCode.TASK_TIMEOUT.getCode(), "task timeout before start");
            }
        }
    }

    // ---------------------------------------------------------------- 执行

    private void execute(Handle handle, Checkpoint checkpoint) {
        String id = handle.id;
        try {
            synchronized (handle) {
                if (handle.cancelRequested) {
                    update(handle, TaskStatus.CANCELLED, handle.record.currentStep(),
                            MeshErrorCode.TASK_CANCELLED.getCode(), "cancelled by user");
                    return;
                }
                if (handle.timeoutRequested) {
                    update(handle, TaskStatus.TIMEOUT, handle.record.currentStep(),
                            MeshErrorCode.TASK_TIMEOUT.getCode(), "task timeout");
                    return;
                }
                update(handle, TaskStatus.RUNNING, handle.record.currentStep(), null, null);
            }

            GenerationRequest request = handle.record.request();
            PipelineHook hook = new PipelineHook() {
                @Override
                public void checkCancelled() {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("interrupted");
                    }
                }

                @Override
                public void afterStep(int step, Mesh mesh) {
                    checkCancelled();
                    store.saveCheckpoint(id, new Checkpoint(step, mesh.snapshot()));
                    update(handle, TaskStatus.RUNNING, step, null, null);
                }
            };

            GenerationResult result = checkpoint == null
                    ? MeshGenerationPipeline.run(request, hook)
                    : MeshGenerationPipeline.resume(request, checkpoint.mesh(),
                            checkpoint.step(), hook);

            // 提交前最后检查：取消/超时与结果提交互斥，绝不提交半成品。
            synchronized (handle) {
                if (handle.cancelRequested || handle.timeoutRequested
                        || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("aborted before commit");
                }
                // 先原子写结果，再翻转状态：崩溃只会让恢复方看到"已提交结果"。
                store.saveResult(id, result);
                store.deleteCheckpoint(id);
                update(handle, TaskStatus.COMPLETED,
                        result.refinementStepsExecuted(), null, null);
            }
        } catch (CancellationException e) {
            synchronized (handle) {
                if (handle.timeoutRequested) {
                    update(handle, TaskStatus.TIMEOUT, handle.record.currentStep(),
                            MeshErrorCode.TASK_TIMEOUT.getCode(), "task timeout");
                } else {
                    update(handle, TaskStatus.CANCELLED, handle.record.currentStep(),
                            MeshErrorCode.TASK_CANCELLED.getCode(), "cancelled by user");
                }
            }
        } catch (MeshException e) {
            fail(handle, e.getErrorCode().getCode(), e.getMessage());
        } catch (Throwable e) {
            fail(handle, MeshErrorCode.INTERNAL_ERROR.getCode(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ScheduledFuture<?> tf = handle.timeoutFuture;
            if (tf != null) {
                tf.cancel(false);
            }
        }
    }

    private void fail(Handle handle, String code, String message) {
        synchronized (handle) {
            if (!handle.record.status().isTerminal()) {
                update(handle, TaskStatus.FAILED, handle.record.currentStep(), code, message);
            }
        }
    }

    private void update(Handle handle, TaskStatus status, int step, String code, String message) {
        handle.record = handle.record.withStatus(status, step, code, message);
        store.saveTask(handle.record);
        if (status.isTerminal()) {
            // 终态任务不再可能需要检查点：清除中间状态，避免半成品残留
            store.deleteCheckpoint(handle.id);
        }
    }

    // ---------------------------------------------------------------- 取消

    public void cancel(String id) {
        Handle handle = require(id);
        synchronized (handle) {
            if (handle.record.status().isTerminal()) {
                throw new MeshException(MeshErrorCode.TASK_STATE_CONFLICT,
                        "task already terminal", Map.of("status", handle.record.status().name()));
            }
            handle.cancelRequested = true;
            Future<?> f = handle.future;
            if (f != null) {
                f.cancel(true);
            }
            if (handle.record.status() == TaskStatus.PENDING) {
                update(handle, TaskStatus.CANCELLED, handle.record.currentStep(),
                        MeshErrorCode.TASK_CANCELLED.getCode(), "cancelled by user");
            }
        }
    }

    // ---------------------------------------------------------------- 查询

    public TaskRecord status(String id) {
        return require(id).record;
    }

    public List<TaskRecord> list() {
        List<TaskRecord> all = new ArrayList<>();
        handles.values().forEach(h -> all.add(h.record));
        all.sort(Comparator.comparing(TaskRecord::createdAt));
        return List.copyOf(all);
    }

    public GenerationResult result(String id) {
        Handle handle = require(id);
        if (handle.record.status() != TaskStatus.COMPLETED) {
            throw new MeshException(MeshErrorCode.TASK_STATE_CONFLICT,
                    "result is only available for COMPLETED tasks",
                    Map.of("status", handle.record.status().name()));
        }
        return store.loadResult(id)
                .orElseThrow(() -> new MeshException(MeshErrorCode.INCONSISTENT_MESH,
                        "result file missing for COMPLETED task"));
    }

    /** 阻塞等待任务终态（测试/同步客户端使用）。 */
    public TaskRecord await(String id, Duration timeout) {
        Handle handle = require(id);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            TaskRecord rec = handle.record;
            if (rec.status().isTerminal()) {
                return rec;
            }
            Future<?> f = handle.future;
            try {
                if (f != null) {
                    f.get(10, TimeUnit.MILLISECONDS);
                } else {
                    Thread.sleep(5);
                }
            } catch (CancellationException | ExecutionException | TimeoutException ignored) {
                // 状态翻转由 execute 内部完成，继续轮询
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return handle.record;
            }
        }
        throw new MeshException(MeshErrorCode.TASK_TIMEOUT, "await timed out");
    }

    // ---------------------------------------------------------------- 细分/粗化

    public GenerationResult refine(String id, Collection<Long> elementIds, int levels) {
        if (levels < 1) {
            throw new MeshException(MeshErrorCode.INVALID_PARAMETER,
                    "refine levels must be >= 1", Map.of("levels", levels));
        }
        Handle handle = require(id);
        synchronized (taskLock(id)) {
            requireCompleted(handle);
            GenerationResult current = result(id);
            Mesh mesh = Mesh.fromSnapshot(current.mesh());
            MeshRefiner.refineElements(mesh, elementIds, levels);
            verifyMesh(mesh, "post-refine");
            QualityReport report = QualityChecker.evaluate(
                    mesh, handle.record.request().thresholds());
            GenerationResult updated = new GenerationResult(mesh.snapshot(), report,
                    current.refinementStepsExecuted(), current.elementLimitReached(),
                    current.seed());
            store.saveResult(id, updated);
            return updated;
        }
    }

    public GenerationResult coarsen(String id, Collection<Long> elementIds) {
        Handle handle = require(id);
        synchronized (taskLock(id)) {
            requireCompleted(handle);
            GenerationResult current = result(id);
            Mesh mesh = Mesh.fromSnapshot(current.mesh());
            MeshCoarsener.coarsen(mesh, elementIds, handle.record.request().thresholds());
            verifyMesh(mesh, "post-coarsen");
            QualityReport report = QualityChecker.evaluate(
                    mesh, handle.record.request().thresholds());
            GenerationResult updated = new GenerationResult(mesh.snapshot(), report,
                    current.refinementStepsExecuted(), current.elementLimitReached(),
                    current.seed());
            store.saveResult(id, updated);
            return updated;
        }
    }

    private void verifyMesh(Mesh mesh, String phase) {
        List<String> problems = mesh.findInconsistencies();
        if (!problems.isEmpty()) {
            throw new MeshException(MeshErrorCode.INCONSISTENT_MESH,
                    phase + " produced inconsistent mesh: " + problems.get(0),
                    Map.of("phase", phase, "problemCount", problems.size()));
        }
    }

    private Object taskLock(String id) {
        return taskLocks.computeIfAbsent(id, k -> new Object());
    }

    private void requireCompleted(Handle handle) {
        if (handle.record.status() != TaskStatus.COMPLETED) {
            throw new MeshException(MeshErrorCode.TASK_STATE_CONFLICT,
                    "operation only allowed on COMPLETED tasks",
                    Map.of("status", handle.record.status().name()));
        }
    }

    private Handle require(String id) {
        Handle handle = handles.get(id);
        if (handle == null) {
            throw new MeshException(MeshErrorCode.TASK_NOT_FOUND, "task not found: " + id);
        }
        return handle;
    }

    // ---------------------------------------------------------------- 恢复

    private void recover() {
        for (String id : store.listTaskIds()) {
            Optional<TaskRecord> loaded = store.loadTask(id);
            if (loaded.isEmpty()) {
                continue;
            }
            TaskRecord rec = loaded.get();
            Handle handle = new Handle(id, rec);
            handles.put(id, handle);

            switch (rec.status()) {
                case COMPLETED -> {
                    if (!store.hasResult(id)) {
                        update(handle, TaskStatus.FAILED, rec.currentStep(),
                                MeshErrorCode.INCONSISTENT_MESH.getCode(),
                                "completed task without result file after restart");
                    }
                }
                case PENDING, RUNNING -> recoverInterrupted(handle, rec);
                default -> {
                    // 终态保持原样
                }
            }
        }
    }

    private void recoverInterrupted(Handle handle, TaskRecord rec) {
        String id = rec.id();
        if (store.hasResult(id)) {
            // 结果已提交但状态未落盘：恢复为完成，绝不把中间状态当最终结果的反面——
            // result.json 只在全部计算结束后写入。
            update(handle, TaskStatus.COMPLETED, rec.currentStep(), null, null);
            store.deleteCheckpoint(id);
            return;
        }
        if (!properties.isAutoResume()) {
            update(handle, TaskStatus.INTERRUPTED, rec.currentStep(),
                    MeshErrorCode.TASK_INTERRUPTED.getCode(),
                    "process restarted before completion; auto-resume disabled");
            return;
        }
        Optional<Checkpoint> checkpoint = store.loadCheckpoint(id);
        checkpoint.ifPresent(cp -> update(handle, TaskStatus.RUNNING, cp.step(), null, null));
        startExecution(handle, checkpoint.orElse(null));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
