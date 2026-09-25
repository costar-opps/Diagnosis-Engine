package org.example.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public class FileCheckpointStore {
    private final ObjectMapper objectMapper;
    private final Path root;

    public FileCheckpointStore(ObjectMapper objectMapper, CheckpointProperties properties) {
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
    }

    public synchronized void save(CheckpointRecord record) {
        validateId(record.getSessionId(), "session_id");
        validateId(record.getTaskId(), "task_id");
        record.setUpdatedAt(System.currentTimeMillis());
        Path directory = root.resolve(record.getSessionId()).normalize();
        Path target = directory.resolve(record.getTaskId() + ".json").normalize();
        ensureInsideRoot(target);
        Path temporary = directory.resolve(record.getTaskId() + ".json.tmp");
        try {
            Files.createDirectories(directory);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), record);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("checkpoint 提交失败: " + record.getTaskId(), e);
        }
    }

    public synchronized Optional<CheckpointRecord> find(String sessionId, String taskId) {
        validateId(sessionId, "session_id");
        validateId(taskId, "task_id");
        Path file = root.resolve(sessionId).resolve(taskId + ".json").normalize();
        ensureInsideRoot(file);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), CheckpointRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("checkpoint 已损坏或无法读取: " + taskId, e);
        }
    }

    public synchronized List<CheckpointRecord> latest(String sessionId, int limit) {
        validateId(sessionId, "session_id");
        Path directory = root.resolve(sessionId).normalize();
        ensureInsideRoot(directory);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            return objectMapper.readValue(path.toFile(), CheckpointRecord.class);
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparingLong(CheckpointRecord::getUpdatedAt).reversed())
                    .limit(Math.max(1, limit))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("无法列出 checkpoint", e);
        }
    }

    private void ensureInsideRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new SecurityException("非法 checkpoint 路径");
        }
    }

    private static void validateId(String id, String field) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{8,100}")) {
            throw new IllegalArgumentException(field + " 格式无效");
        }
    }
}
