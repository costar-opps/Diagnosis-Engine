package org.example.checkpoint;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

@Service
public class SessionIdentityService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public SessionIdentityService(CheckpointProperties properties) {
        String configured = properties.getHmacSecret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "checkpoint.hmac-secret 未配置；请通过 CHECKPOINT_HMAC_SECRET 提供至少 32 字符的随机密钥");
        }
        if (configured.length() < 32) {
            throw new IllegalStateException("checkpoint.hmac-secret 长度不能少于 32 字符");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
    }

    public SessionCredential issue() {
        String sessionId = "ses-" + UUID.randomUUID();
        return new SessionCredential(sessionId, sign(sessionId));
    }

    public boolean verify(String sessionId, String token) {
        if (sessionId == null || token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(sessionId).getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
    }

    public void requireValid(String sessionId, String token) {
        if (!verify(sessionId, token)) {
            throw new SecurityException("无效的会话身份");
        }
    }

    private String sign(String sessionId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法签发会话身份", e);
        }
    }

    public record SessionCredential(String sessionId, String sessionToken) {
    }
}
