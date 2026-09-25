package org.example.diagnosis.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.example.diagnosis.DiagnosisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Service
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    private final DiagnosisProperties properties;
    private final ProjectProfileValidator validator;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final AtomicReference<Map<String, RegisteredProject>> snapshot =
            new AtomicReference<>(Map.of());

    public ProjectRegistry(DiagnosisProperties properties, ProjectProfileValidator validator) {
        this.properties = properties;
        this.validator = validator;
    }

    @PostConstruct
    public void init() {
        try {
            reload();
        } catch (Exception e) {
            log.error("项目档案首次加载失败，注册表为空: {}", e.getMessage());
        }
    }

    public synchronized void reload() {
        Path dir = Paths.get(properties.getProjectsDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("档案目录不存在: " + dir);
        }
        Map<String, RegisteredProject> loaded = loadFrom(dir);
        snapshot.set(Map.copyOf(loaded));
        log.info("项目档案已加载 {} 个（可诊断 {}）",
                loaded.size(),
                loaded.values().stream().filter(RegisteredProject::isDiagnosable).count());
    }

    /**
     * 重载失败时保持原表不变。
     */
    public synchronized boolean tryReload() {
        Map<String, RegisteredProject> previous = snapshot.get();
        try {
            reload();
            return true;
        } catch (Exception e) {
            snapshot.set(previous);
            log.warn("档案重载失败，已保持原注册表: {}", e.getMessage());
            return false;
        }
    }

    public List<RegisteredProject> list() {
        return new ArrayList<>(snapshot.get().values());
    }

    public Optional<RegisteredProject> find(String projectId) {
        if (projectId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get().get(projectId));
    }

    public RegisteredProject requireDiagnosable(String projectId) {
        RegisteredProject project = find(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目未注册: " + projectId));
        if (!project.isDiagnosable()) {
            throw new IllegalArgumentException("项目不可诊断: " + projectId + "，原因: " + project.getReason());
        }
        return project;
    }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            snapshot.get().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> digest.update((entry.getKey() + "=" + entry.getValue())
                            .getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("无法计算项目档案版本", e);
        }
    }

    Map<String, RegisteredProject> loadFrom(Path dir) {
        List<LoadedFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> {
                String name = path.getFileName().toString().toLowerCase();
                return name.endsWith(".yml") || name.endsWith(".yaml");
            }).sorted().forEach(path -> {
                try {
                    ProjectProfile profile = yamlMapper.readValue(path.toFile(), ProjectProfile.class);
                    files.add(new LoadedFile(path, profile, null));
                } catch (IOException e) {
                    files.add(new LoadedFile(path, null, "YAML 解析失败: " + e.getMessage()));
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("读取档案目录失败: " + dir, e);
        }

        Set<String> declaredIds = files.stream()
                .filter(f -> f.profile != null && f.profile.getIdentity() != null && f.profile.getIdentity().getId() != null)
                .map(f -> f.profile.getIdentity().getId())
                .collect(java.util.stream.Collectors.toSet());

        Map<String, RegisteredProject> result = new LinkedHashMap<>();
        for (LoadedFile file : files) {
            if (file.profile == null) {
                String fallbackId = file.path.getFileName().toString().replaceAll("\\.ya?ml$", "");
                result.put(fallbackId, RegisteredProject.builder()
                        .profile(emptyProfile(fallbackId))
                        .diagnosable(false)
                        .reason(file.parseError)
                        .build());
                continue;
            }
            String id = file.profile.getIdentity().getId();
            if (id != null && result.containsKey(id)) {
                RegisteredProject existing = result.get(id);
                existing.getWarnings().add("后续档案 " + file.path.getFileName() + " 声明了重复标识，已拒绝");
                continue;
            }
            ProjectProfileValidator.ValidationResult validation = validator.validate(file.profile, declaredIds);
            result.put(id, RegisteredProject.builder()
                    .profile(file.profile)
                    .diagnosable(validation.valid())
                    .reason(validation.reason())
                    .warnings(new ArrayList<>(validation.warnings()))
                    .build());
        }
        return result;
    }

    private static ProjectProfile emptyProfile(String id) {
        ProjectProfile profile = new ProjectProfile();
        profile.getIdentity().setId(id);
        profile.getIdentity().setName(id);
        return profile;
    }

    private record LoadedFile(Path path, ProjectProfile profile, String parseError) {
    }
}
