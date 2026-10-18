package com.wordtrail.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Component;

@Component
public class PasswordService {
    private static final String PREFIX = "{bcrypt-sha256}";
    private final String dummyHash = BCrypt.hashpw("dummy-password-for-timing", BCrypt.gensalt(12));

    public String hash(String raw) {
        // Preserve the existing versioned SHA-256 prehash format for passwords over BCrypt's
        // 72-byte limit. Both SHA-256 and BCrypt are standard library algorithms.
        if (raw.getBytes(StandardCharsets.UTF_8).length > 72) {
            return PREFIX + BCrypt.hashpw(digest(raw), BCrypt.gensalt(12));
        }
        return BCrypt.hashpw(raw, BCrypt.gensalt(12));
    }

    public boolean matches(String raw, String hash) {
        if (raw == null) { return false; }
        if (hash == null) {
            BCrypt.checkpw(raw.getBytes(StandardCharsets.UTF_8).length > 72 ? digest(raw) : raw, dummyHash);
            return false;
        }
        try {
            if (hash.startsWith(PREFIX)) {
                return BCrypt.checkpw(digest(raw), compatible(hash.substring(PREFIX.length())));
            }
            if (raw.getBytes(StandardCharsets.UTF_8).length > 72) {
                BCrypt.checkpw(digest(raw), dummyHash);
                return false;
            }
            return BCrypt.checkpw(raw, compatible(hash));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String compatible(String hash) {
        // jBCrypt 0.4 uses the 2a marker; existing corrected 2b/2y hashes use the same algorithm.
        if (hash.startsWith("$2b$") || hash.startsWith("$2y$")) {
            return "$2a$" + hash.substring(4);
        }
        return hash;
    }

    private String digest(String raw) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
