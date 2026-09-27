package com.github.guoyyit.meshgenerationjava.tests;

import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerationOptions;
import com.github.guoyyit.meshgenerationjava.generator.MeshGenerator;
import com.github.guoyyit.meshgenerationjava.geometry.Point2D;
import com.github.guoyyit.meshgenerationjava.geometry.Region;
import com.github.guoyyit.meshgenerationjava.io.MeshSerializer;
import com.github.guoyyit.meshgenerationjava.task.MeshTask;
import com.github.guoyyit.meshgenerationjava.task.MeshTaskService;
import com.github.guoyyit.meshgenerationjava.task.TaskStatus;
import com.github.guoyyit.meshgenerationjava.task.TaskStore;
import com.github.guoyyit.meshgenerationjava.validation.ErrorCode;
import com.github.guoyyit.meshgenerationjava.validation.MeshValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurrent tasks are isolated; cancel/timeout never leave a half-committed result. */
class ConcurrencyTest {

    @TempDir
    Path storeDir;

    private static Region square(double size) {
        return new Region(List.of(
                new Point2D(0, 0), new Point2D(size, 0),
                new Point2D(size, size), new Point2D(0, size)), List.of());
    }

    private static MeshTask awaitTerminal(MeshTaskService service, String id, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            MeshTask task = service.getTask(id);
            TaskStatus s = task.getStatus();
            if (s == TaskStatus.COMPLETED || s == TaskStatus.FAILED || s == TaskStatus.CANCELLED) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("task did not reach a terminal state in time");
    }

    @Test
    void concurrentTasksAreIsolatedAndMatchSingleThreaded() throws Exception {
        MeshTaskService service = new MeshTaskService(new TaskStore(storeDir), 4, 120_000);
        try {
            MeshSerializer serializer = new MeshSerializer();
            List<String> ids = new ArrayList<>();
            List<Region> regions = new ArrayList<>();
            List<MeshGenerationOptions> optionsList = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Region region = square(1.0 + 0.5 * i);
                MeshGenerationOptions options = new MeshGenerationOptions();
                options.setSeed(1000L + i);
                options.setMaxEdgeLength(0.4);
                options.setMinAngleDeg(3.0);
                regions.add(region);
                optionsList.add(options);
                ids.add(service.submit(region, options));
            }
            for (int i = 0; i < ids.size(); i++) {
                MeshTask task = awaitTerminal(service, ids.get(i), 60_000);
                assertEquals(TaskStatus.COMPLETED, task.getStatus(),
                        "task " + i + " failed: " + task.getError());
                // Isolation: concurrent result must equal a fresh single-threaded run.
                Mesh expected = new MeshGenerator().generate(regions.get(i), optionsList.get(i)).mesh();
                Mesh actual = service.getResult(ids.get(i));
                assertEquals(serializer.toJson(expected), serializer.toJson(actual),
                        "task " + i + " result diverged under concurrency");
            }
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cancelledTaskLeavesNoResult() throws Exception {
        MeshTaskService service = new MeshTaskService(new TaskStore(storeDir), 2, 120_000);
        try {
            MeshGenerationOptions options = new MeshGenerationOptions();
            options.setSeed(5L);
            options.setMaxEdgeLength(0.1); // heavy enough to still run at cancel time
            options.setMinAngleDeg(3.0);
            String id = service.submit(square(6.0), options);
            Thread.sleep(150);
            boolean cancelRequested = service.cancel(id);
            MeshTask task = awaitTerminal(service, id, 60_000);
            if (cancelRequested) {
                assertEquals(TaskStatus.CANCELLED, task.getStatus());
                assertEquals(ErrorCode.TASK_CANCELLED.name(), task.getErrorCode());
                MeshValidationException ex = assertThrows(MeshValidationException.class,
                        () -> service.getResult(id));
                assertEquals(ErrorCode.TASK_NOT_COMPLETED, ex.getCode());
            } else {
                // Finished before the cancel landed: then the result must be complete.
                assertEquals(TaskStatus.COMPLETED, task.getStatus());
                assertTrue(service.getResult(id).checkConsistency().isEmpty());
            }
        } finally {
            service.shutdown();
        }
    }

    @Test
    void timedOutTaskFailsWithoutResult() throws Exception {
        MeshTaskService service = new MeshTaskService(new TaskStore(storeDir), 2, 120_000);
        try {
            MeshGenerationOptions options = new MeshGenerationOptions();
            options.setSeed(5L);
            options.setMaxEdgeLength(0.1);
            options.setMinAngleDeg(3.0);
            options.setTimeoutMillis(1); // expires almost immediately
            String id = service.submit(square(6.0), options);
            MeshTask task = awaitTerminal(service, id, 60_000);
            assertEquals(TaskStatus.FAILED, task.getStatus());
            assertEquals(ErrorCode.TASK_TIMEOUT.name(), task.getErrorCode());
            MeshValidationException ex = assertThrows(MeshValidationException.class,
                    () -> service.getResult(id));
            assertEquals(ErrorCode.TASK_NOT_COMPLETED, ex.getCode());
        } finally {
            service.shutdown();
        }
    }
}
