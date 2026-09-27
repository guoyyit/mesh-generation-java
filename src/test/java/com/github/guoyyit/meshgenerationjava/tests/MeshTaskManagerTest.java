package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.geometry.Point2;
import com.github.guoyyit.meshgenerationjava.mesh.model.Mesh;
import com.github.guoyyit.meshgenerationjava.mesh.model.PlanarRegion;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationRequest;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationResult;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.MeshGenerationPipeline;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.PipelineHook;
import com.github.guoyyit.meshgenerationjava.mesh.quality.QualityThresholds;
import com.github.guoyyit.meshgenerationjava.service.Checkpoint;
import com.github.guoyyit.meshgenerationjava.service.MeshProperties;
import com.github.guoyyit.meshgenerationjava.service.MeshTaskManager;
import com.github.guoyyit.meshgenerationjava.service.TaskRecord;
import com.github.guoyyit.meshgenerationjava.service.TaskStatus;
import com.github.guoyyit.meshgenerationjava.service.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshTaskManagerTest {

    @TempDir
    Path tempDir;

    private MeshProperties props(Path dir) {
        MeshProperties p = new MeshProperties();
        p.setStoreDir(dir.toString());
        p.setWorkerThreads(4);
        p.setTaskTimeout(Duration.ofMinutes(1));
        p.setAutoResume(true);
        p.setMaxElements(100_000);
        return p;
    }

    private static GenerationRequest lightRequest(long seed) {
        return new GenerationRequest(
                MeshTestSupport.squareWithHole(4.0, 2.0),
                new QualityThresholds(null, 1.0, null, null),
                seed, 20, 50_000);
    }

    private static GenerationRequest heavyRequest() {
        return new GenerationRequest(
                MeshTestSupport.square(4.0),
                new QualityThresholds(null, 0.005, null, null),
                0L, 60, 300_000);
    }

    // ---------------------------------------------------------------- 并发

    @Test
    void concurrentTasksAreIsolatedAndComplete() {
        MeshTaskManager manager = new MeshTaskManager(props(tempDir));
        try {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                ids.add(manager.submit(lightRequest(i)));
            }
            for (String id : ids) {
                TaskRecord rec = manager.await(id, Duration.ofSeconds(60));
                assertEquals(TaskStatus.COMPLETED, rec.status(),
                        "task " + id + " failed: " + rec.errorMessage());
                GenerationResult result = manager.result(id);
                MeshTestSupport.assertConsistent(result.mesh());
                assertTrue(result.qualitySatisfied());
                assertEquals(id, rec.id());
            }
            // 相同种子的两个任务结果必须一致（隔离但不影响确定性）
            String a = manager.submit(lightRequest(1000L));
            String b = manager.submit(lightRequest(1000L));
            manager.await(a, Duration.ofSeconds(60));
            manager.await(b, Duration.ofSeconds(60));
            assertEquals(manager.result(a).mesh(), manager.result(b).mesh());
        } finally {
            manager.close();
        }
    }

    // ---------------------------------------------------------------- 取消

    @Test
    void cancelPendingTaskLeavesNoResult() {
        MeshProperties p = props(tempDir);
        p.setWorkerThreads(1);
        MeshTaskManager manager = new MeshTaskManager(p);
        try {
            String heavy = manager.submit(heavyRequest());
            String queued = manager.submit(lightRequest(1L));
            // 等重任务占住唯一线程，轻任务仍在排队
            waitFor(() -> manager.status(heavy).status() == TaskStatus.RUNNING, 10_000);
            manager.cancel(queued);
            TaskRecord rec = manager.await(queued, Duration.ofSeconds(10));
            assertEquals(TaskStatus.CANCELLED, rec.status());
            assertEquals(MeshErrorCode.TASK_CANCELLED.getCode(), rec.errorCode());
            MeshException e = assertThrows(MeshException.class, () -> manager.result(queued));
            assertEquals(MeshErrorCode.TASK_STATE_CONFLICT, e.getErrorCode());
            assertFalse(new TaskStore(tempDir).hasResult(queued), "cancelled task must have no result");
            manager.cancel(heavy);
            manager.await(heavy, Duration.ofSeconds(60));
        } finally {
            manager.close();
        }
    }

    @Test
    void cancelRunningTaskLeavesNoResult() {
        MeshTaskManager manager = new MeshTaskManager(props(tempDir));
        try {
            String id = manager.submit(heavyRequest());
            waitFor(() -> manager.status(id).status() == TaskStatus.RUNNING, 10_000);
            manager.cancel(id);
            TaskRecord rec = manager.await(id, Duration.ofSeconds(60));
            assertEquals(TaskStatus.CANCELLED, rec.status());
            assertFalse(new TaskStore(tempDir).hasResult(id));
            MeshException e = assertThrows(MeshException.class, () -> manager.result(id));
            assertEquals(MeshErrorCode.TASK_STATE_CONFLICT, e.getErrorCode());
        } finally {
            manager.close();
        }
    }

    @Test
    void cancelTerminalTaskIsRejected() {
        MeshTaskManager manager = new MeshTaskManager(props(tempDir));
        try {
            String id = manager.submit(lightRequest(5L));
            manager.await(id, Duration.ofSeconds(60));
            MeshException e = assertThrows(MeshException.class, () -> manager.cancel(id));
            assertEquals(MeshErrorCode.TASK_STATE_CONFLICT, e.getErrorCode());
        } finally {
            manager.close();
        }
    }

    // ---------------------------------------------------------------- 超时

    @Test
    void timeoutLeavesNoHalfFinishedResult() {
        MeshProperties p = props(tempDir);
        p.setTaskTimeout(Duration.ofMillis(300));
        MeshTaskManager manager = new MeshTaskManager(p);
        try {
            String id = manager.submit(heavyRequest());
            TaskRecord rec = manager.await(id, Duration.ofSeconds(120));
            assertEquals(TaskStatus.TIMEOUT, rec.status());
            assertEquals(MeshErrorCode.TASK_TIMEOUT.getCode(), rec.errorCode());
            assertFalse(new TaskStore(tempDir).hasResult(id));
            // 超时后服务仍可用
            String ok = manager.submit(lightRequest(7L));
            assertEquals(TaskStatus.COMPLETED,
                    manager.await(ok, Duration.ofSeconds(60)).status());
        } finally {
            manager.close();
        }
    }

    // ---------------------------------------------------------------- 恢复

    @Test
    void completedTaskSurvivesRestart() {
        String id;
        GenerationResult expected;
        MeshTaskManager first = new MeshTaskManager(props(tempDir));
        try {
            id = first.submit(lightRequest(11L));
            first.await(id, Duration.ofSeconds(60));
            expected = first.result(id);
        } finally {
            first.close();
        }
        MeshTaskManager second = new MeshTaskManager(props(tempDir));
        try {
            assertEquals(TaskStatus.COMPLETED, second.status(id).status());
            assertEquals(expected.mesh(), second.result(id).mesh());
        } finally {
            second.close();
        }
    }

    @Test
    void crashedTaskResumesFromCheckpointWithoutRepeatingSteps() {
        TaskStore store = new TaskStore(tempDir);
        GenerationRequest request = lightRequest(23L);

        // 基线：完整跑完
        GenerationResult baseline = MeshGenerationPipeline.run(request, PipelineHook.NOOP);

        // 模拟崩溃：任务 RUNNING，检查点写到第 1 轮细化后
        String taskId = "crashed-task";
        TaskRecord running = new TaskRecord(taskId, TaskStatus.RUNNING, request, -1,
                null, null, Instant.now(), Instant.now());
        store.saveTask(running);
        PipelineHook crashingHook = new PipelineHook() {
            @Override
            public void checkCancelled() {
            }

            @Override
            public void afterStep(int step, Mesh mesh) {
                store.saveCheckpoint(taskId, new Checkpoint(step, mesh.snapshot()));
                if (step >= 1) {
                    throw new CancellationException("simulated crash");
                }
            }
        };
        assertThrows(CancellationException.class,
                () -> MeshGenerationPipeline.run(request, crashingHook));
        assertTrue(store.loadCheckpoint(taskId).isPresent());

        // 重启恢复：应续跑完成，且结果与基线逐项一致
        MeshTaskManager manager = new MeshTaskManager(store, props(tempDir), true);
        try {
            TaskRecord rec = manager.await(taskId, Duration.ofSeconds(60));
            assertEquals(TaskStatus.COMPLETED, rec.status());
            GenerationResult recovered = manager.result(taskId);
            assertEquals(baseline.mesh(), recovered.mesh(),
                    "recovered result must equal uninterrupted baseline");
            assertEquals(baseline.refinementStepsExecuted(), recovered.refinementStepsExecuted());
            assertFalse(store.loadCheckpoint(taskId).isPresent(),
                    "checkpoint must be cleaned after completion");
        } finally {
            manager.close();
        }
    }

    @Test
    void unfinishedTaskIsDistinguishableFromCommitted() {
        TaskStore store = new TaskStore(tempDir);
        GenerationRequest request = lightRequest(31L);
        String taskId = "interrupted-task";
        store.saveTask(new TaskRecord(taskId, TaskStatus.RUNNING, request, -1,
                null, null, Instant.now(), Instant.now()));

        MeshProperties p = props(tempDir);
        p.setAutoResume(false);
        MeshTaskManager manager = new MeshTaskManager(store, p, true);
        try {
            TaskRecord rec = manager.status(taskId);
            assertEquals(TaskStatus.INTERRUPTED, rec.status());
            assertEquals(MeshErrorCode.TASK_INTERRUPTED.getCode(), rec.errorCode());
            assertThrows(MeshException.class, () -> manager.result(taskId));
        } finally {
            manager.close();
        }
    }

    @Test
    void committedResultWithoutStatusFlipIsFinalizedOnRecovery() {
        TaskStore store = new TaskStore(tempDir);
        GenerationRequest request = lightRequest(37L);
        String taskId = "almost-committed";
        // 结果已原子提交，但状态仍是 RUNNING（崩溃发生在状态翻转前）
        GenerationResult result = MeshGenerationPipeline.run(request, PipelineHook.NOOP);
        store.saveResult(taskId, result);
        store.saveTask(new TaskRecord(taskId, TaskStatus.RUNNING, request, 3,
                null, null, Instant.now(), Instant.now()));

        MeshTaskManager manager = new MeshTaskManager(store, props(tempDir), true);
        try {
            assertEquals(TaskStatus.COMPLETED, manager.status(taskId).status());
            assertEquals(result.mesh(), manager.result(taskId).mesh());
        } finally {
            manager.close();
        }
    }

    // ---------------------------------------------------------------- 失败隔离

    @Test
    void invalidTaskFailsWithoutAffectingOthers() {
        MeshTaskManager manager = new MeshTaskManager(props(tempDir));
        try {
            GenerationRequest bad = new GenerationRequest(
                    new PlanarRegion(List.of(
                            new Point2(0, 0), new Point2(1, 0), new Point2(1, 0), new Point2(0, 1)),
                            List.of()),
                    QualityThresholds.none(), 0L, 5, 1000);
            String badId = manager.submit(bad);
            String goodId = manager.submit(lightRequest(3L));

            TaskRecord badRec = manager.await(badId, Duration.ofSeconds(30));
            assertEquals(TaskStatus.FAILED, badRec.status());
            assertEquals(MeshErrorCode.DUPLICATE_POINT.getCode(), badRec.errorCode());

            assertEquals(TaskStatus.COMPLETED,
                    manager.await(goodId, Duration.ofSeconds(60)).status());
            assertNotNull(manager.result(goodId));
        } finally {
            manager.close();
        }
    }

    // ---------------------------------------------------------------- 细分/粗化接口

    @Test
    void refineAndCoarsenOnCommittedMesh() {
        MeshTaskManager manager = new MeshTaskManager(props(tempDir));
        try {
            String id = manager.submit(new GenerationRequest(
                    MeshTestSupport.square(2.0), QualityThresholds.none(), 0L, 0, 10_000));
            manager.await(id, Duration.ofSeconds(60));
            GenerationResult base = manager.result(id);
            int baseCount = base.mesh().elements().size();

            List<Long> ids = base.mesh().elements().stream().map(e -> e.id()).toList();
            GenerationResult refined = manager.refine(id, ids, 2);
            assertTrue(refined.mesh().elements().size() > baseCount);
            MeshTestSupport.assertConsistent(refined.mesh());

            GenerationResult coarsened = manager.coarsen(id, null);
            assertEquals(baseCount, coarsened.mesh().elements().size());
            MeshTestSupport.assertConsistent(coarsened.mesh());

            // 持久化后的结果与返回值一致
            assertEquals(coarsened.mesh(), manager.result(id).mesh());
        } finally {
            manager.close();
        }
    }

    private static void waitFor(Check check, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("condition not met within " + timeoutMillis + "ms");
    }

    private interface Check {
        boolean ok();
    }
}
