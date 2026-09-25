package org.example.checkpoint;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/checkpoint")
public class CheckpointController {
    public static final String SESSION_HEADER = "X-Session-Id";
    public static final String TOKEN_HEADER = "X-Session-Token";

    private final SessionIdentityService identities;
    private final CheckpointService checkpoints;

    public CheckpointController(SessionIdentityService identities, CheckpointService checkpoints) {
        this.identities = identities;
        this.checkpoints = checkpoints;
    }

    @PostMapping("/sessions")
    public Map<String, String> issueSession() {
        SessionIdentityService.SessionCredential credential = identities.issue();
        return Map.of("session_id", credential.sessionId(), "session_token", credential.sessionToken());
    }

    @GetMapping("/tasks")
    public ResponseEntity<?> latest(@RequestHeader(SESSION_HEADER) String sessionId,
                                    @RequestHeader(TOKEN_HEADER) String token,
                                    @RequestParam(defaultValue = "10") int limit) {
        try {
            List<CheckpointRecord> records = checkpoints.latest(sessionId, token, Math.min(limit, 50));
            return ResponseEntity.ok(records);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "无权访问任务"));
        }
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> task(@PathVariable String taskId,
                                  @RequestHeader(SESSION_HEADER) String sessionId,
                                  @RequestHeader(TOKEN_HEADER) String token) {
        try {
            return ResponseEntity.ok(checkpoints.requireOwned(sessionId, token, taskId));
        } catch (CheckpointService.CheckpointVersionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "任务不存在"));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "无权访问任务"));
        }
    }
}
