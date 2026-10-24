package com.wordtrail.user.vo;
import java.time.LocalDateTime;
public record AuthSessionVO(String sessionId,String deviceName,boolean current,
        LocalDateTime createdAt,LocalDateTime lastRefreshedAt,LocalDateTime expiresAt) {}
