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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Committed results survive a restart; interrupted tasks are distinguishable and resumable. */
class RecoveryTest {

    @TempDir
    Path storeDir;

    private static Region square(double size) {
        return new Region(List.of(
                new Point2D(0, 0), new Point2D(size, 0),
                new Point2D(size, size), new Point2D(0, size)), List.of());
    }

    private static MeshGenerationOptions options(long seed, double maxEdge) {
        MeshGenerationOptions options = new MeshGenerationOptions();
        options.setSeed(seed);
        options.setMaxEdgeLength(maxEdge);
        options.setMinAngleDeg(3.0);
        return options;
    }

    private static MeshTask awaitTerminal(MeshTaskService service, String id, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            MeshTask task = service.getTask(id);
            if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED
                    || task.getStatus() == TaskStatus.CANCELLED) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("task did not reach a terminal state in time");
    }

    @Test
    void committedSurvivesInterruptedIsResumable() throws Exception {
        TaskStore store = new TaskStore(storeDir);
        MeshSerializer serializer = new MeshSerializer();

        // Phase 1: a task completes; another is left RUNNING by a "crashed" process.
        MeshTaskService service1 = new MeshTaskService(store, 2, 60_000);
        String committedId = service1.submit(square(2.0), options(42L, 0.5));
        assertEquals(TaskStatus.COMPLETED, awaitTerminal(service1, committedId, 60_000).getStatus());
        String committedJson = serializer.toJson(service1.getResult(committedId));

        Region crashedRegion = square(3.0);
        MeshGenerationOptions crashedOptions = options(77L, 0.4);
        MeshTask crashed = new MeshTask();
        crashed.setId("crashed-task");
        crashed.setStatus(TaskStatus.RUNNING); // leftover of an abruptly killed process
        crashed.setRegion(crashedRegion);
        crashed.setOptions(crashedOptions);
        crashed.setCreatedAt(System.currentTimeMillis());
        crashed.setUpdatedAt(System.currentTimeMillis());
        store.saveTask(crashed);

        // Orphan: result file written but status never flipped (crash between the two writes).
        MeshTask orphan = new MeshTask();
        orphan.setId("orphan-task");
        orphan.setStatus(TaskStatus.RUNNING);
        orphan.setRegion(crashedRegion);
        orphan.setOptions(crashedOptions);
        orphan.setCreatedAt(System.currentTimeMillis());
        orphan.setUpdatedAt(System.currentTimeMillis());
        store.saveTask(orphan);
        store.saveResult("orphan-task", new MeshGenerator().generate(crashedRegion, crashedOptions).mesh());

        service1.shutdown();

        // Phase 2: a new process recovers the store.
        MeshTaskService service2 = new MeshTaskService(new TaskStore(storeDir), 2, 60_000);
        try {
            int marked = service2.recover();
            assertEquals(2, marked, "both leftover RUNNING tasks must be marked");

            // Committed result: still complete and bit-identical.
            assertEquals(TaskStatus.COMPLETED, service2.getTask(committedId).getStatus());
            assertEquals(committedJson, serializer.toJson(service2.getResult(committedId)));

            // Interrupted tasks: distinguishable, results not readable as final.
            assertEquals(TaskStatus.INTERRUPTED, service2.getTask("crashed-task").getStatus());
            assertEquals(TaskStatus.INTERRUPTED, service2.getTask("orphan-task").getStatus());
            MeshValidationException ex1 = assertThrows(MeshValidationException.class,
                    () -> service2.getResult("crashed-task"));
            assertEquals(ErrorCode.TASK_NOT_COMPLETED, ex1.getCode());
            // Even with an orphan result file present, it is not served as final.
            MeshValidationException ex2 = assertThrows(MeshValidationException.class,
                    () -> service2.getResult("orphan-task"));
            assertEquals(ErrorCode.TASK_NOT_COMPLETED, ex2.getCode());

            // Resubmit reruns from scratch: same seed => identical mesh, no duplicated
            // or skipped refinement steps.
            String newId = service2.resubmit("crashed-task");
            assertEquals(TaskStatus.COMPLETED, awaitTerminal(service2, newId, 60_000).getStatus());
            Mesh expected = new MeshGenerator().generate(crashedRegion, crashedOptions).mesh();
            assertEquals(serializer.toJson(expected), serializer.toJson(service2.getResult(newId)));
        } finally {
            service2.shutdown();
        }
    }

    @Test
    void resubmitOfCompletedTaskIsRejected() throws Exception {
        MeshTaskService service = new MeshTaskService(new TaskStore(storeDir), 1, 60_000);
        try {
            String id = service.submit(square(1.0), options(1L, 0.5));
            assertEquals(TaskStatus.COMPLETED, awaitTerminal(service, id, 60_000).getStatus());
            assertThrows(MeshValidationException.class, () -> service.resubmit(id));
        } finally {
            service.shutdown();
        }
    }
}
