package com.github.guoyyit.meshgenerationjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshErrorCode;
import com.github.guoyyit.meshgenerationjava.mesh.error.MeshException;
import com.github.guoyyit.meshgenerationjava.mesh.pipeline.GenerationResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 基于文件的任务持久化。所有写入均为"临时文件 + 原子改名"，
 * 保证任何时刻读到的文件要么完整要么不存在（无半成品状态）。
 *
 * <p>目录布局：{storeDir}/{taskId}/task.json | checkpoint.json | result.json</p>
 */
public class TaskStore {

    private final Path root;
    private final ObjectMapper mapper;

    public TaskStore(Path root) {
        this.root = root;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new MeshException(MeshErrorCode.PERSISTENCE_ERROR,
                    "cannot create store dir " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    private Path taskDir(String taskId) {
        return root.resolve(taskId);
    }

    private Path taskFile(String taskId) {
        return taskDir(taskId).resolve("task.json");
    }

    private Path checkpointFile(String taskId) {
        return taskDir(taskId).resolve("checkpoint.json");
    }

    private Path resultFile(String taskId) {
        return taskDir(taskId).resolve("result.json");
    }

    // ---------------------------------------------------------------- 写入

    public void saveTask(TaskRecord record) {
        writeAtomic(taskFile(record.id()), record);
    }

    public void saveCheckpoint(String taskId, Checkpoint checkpoint) {
        writeAtomic(checkpointFile(taskId), checkpoint);
    }

    public void saveResult(String taskId, GenerationResult result) {
        writeAtomic(resultFile(taskId), result);
    }

    private void writeAtomic(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), value);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new MeshException(MeshErrorCode.PERSISTENCE_ERROR,
                    "failed to write " + file, e);
        }
    }

    // ---------------------------------------------------------------- 读取

    public Optional<TaskRecord> loadTask(String taskId) {
        return read(taskFile(taskId), TaskRecord.class);
    }

    public Optional<Checkpoint> loadCheckpoint(String taskId) {
        return read(checkpointFile(taskId), Checkpoint.class);
    }

    public Optional<GenerationResult> loadResult(String taskId) {
        return read(resultFile(taskId), GenerationResult.class);
    }

    public boolean hasResult(String taskId) {
        return Files.exists(resultFile(taskId));
    }

    private <T> Optional<T> read(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), type));
        } catch (IOException e) {
            throw new MeshException(MeshErrorCode.PERSISTENCE_ERROR,
                    "corrupt store file " + file, e);
        }
    }

    /** 扫描全部任务 id（用于重启恢复）。 */
    public List<String> listTaskIds() {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return ids;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path p : stream) {
                if (Files.isDirectory(p) && Files.exists(p.resolve("task.json"))) {
                    ids.add(p.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    public void deleteCheckpoint(String taskId) {
        try {
            Files.deleteIfExists(checkpointFile(taskId));
        } catch (IOException e) {
            throw new MeshException(MeshErrorCode.PERSISTENCE_ERROR,
                    "failed to delete checkpoint for " + taskId, e);
        }
    }
}
