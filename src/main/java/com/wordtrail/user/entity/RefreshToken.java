package com.wordtrail.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Table("refresh_token")
public class RefreshToken {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long userId;
    @JsonIgnore
    private String tokenHash;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
}
