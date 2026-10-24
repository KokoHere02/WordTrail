package com.wordtrail.user.entity;
import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Table("user_auth_session")
public class AuthSession {
    @Id private String id;
    private Long userId;
    private String deviceName;
    private LocalDateTime createdAt;
    private LocalDateTime lastRefreshedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
}
