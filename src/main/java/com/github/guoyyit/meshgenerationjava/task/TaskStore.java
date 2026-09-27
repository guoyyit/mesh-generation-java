package com.github.guoyyit.meshgenerationjava.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.meshgenerationjava.core.Mesh;
import com.github.guoyyit.meshgenerationjava.io.MeshSerializer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed task/result store. Writes are atomic (temp file + move) so a
 * committed result is always complete and readable; a crash never leaves a
 * half-written state behind.
 */
public class TaskStore {

    private final Path tasksDir;
    private final Path resultsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MeshSerializer serializer = new MeshSerializer();

    public TaskStore(Path dir) {
        this.tasksDir = dir.resolve("tasks");
        this.resultsDir = dir.resolve("results");
        try {
            Files.createDirectories(tasksDir);
            Files.createDirectories(resultsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void saveTask(MeshTask task) {
        try {
            writeAtomic(tasksDir.resolve(task.getId() + ".json"),
                    mapper.writeValueAsString(task));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<MeshTask> loadTask(String id) {
        Path file = tasksDir.resolve(id + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(Files.readString(file), MeshTask.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<MeshTask> listTasks() {
        try (Stream<Path> stream = Files.list(tasksDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .map(this::loadTask)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(MeshTask::getId))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void saveResult(String taskId, Mesh mesh) {
        try {
            writeAtomic(resultsDir.resolve(taskId + ".json"), serializer.toJson(mesh));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<Mesh> loadResult(String taskId) {
        Path file = resultsDir.resolve(taskId + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(serializer.fromJson(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Temp file + atomic move: readers only ever see complete files. */
    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
