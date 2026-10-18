package com.wordtrail.user.service;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.security.*;
import com.wordtrail.user.dto.*;
import com.wordtrail.user.entity.*;
import com.wordtrail.user.mapper.*;
import com.wordtrail.user.vo.*;
import com.mybatisflex.core.query.QueryWrapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service
public class UserService {
    private final UserMapper users;
    private final RefreshTokenMapper refreshTokens;
    private final PasswordService passwords;
    private final JwtTokenService jwt;
    private final Clock clock;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserMapper users, RefreshTokenMapper refreshTokens, PasswordService passwords,
            JwtTokenService jwt, Clock clock, @Value("${app.refresh-token.ttl:7d}") Duration refreshTtl) {
        if (refreshTtl == null || refreshTtl.toSeconds() < 1) {
            throw new IllegalStateException("Refresh Token TTL must be at least one second");
        }
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.jwt = jwt;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    @Transactional
    public UserVO register(RegisterRequest request) {
        String username = request.getUsername().toLowerCase(Locale.ROOT);
        if (users.selectCountByQuery(QueryWrapper.create().where(column("username").eq(username))) > 0) {
            throw duplicate();
        }
        var account = new UserAccount();
        account.setUsername(username);
        account.setPasswordHash(passwords.hash(request.getPassword()));
        account.setNickname(request.getNickname());
        try {
            users.insertSelective(account);
        } catch (DuplicateKeyException ex) {
            throw duplicate();
        }
        return UserVO.from(account);
    }

    @Transactional
    public TokenVO login(LoginRequest request) {
        var account = users.selectOneByQuery(QueryWrapper.create()
            .where(column("username").eq(request.getUsername().toLowerCase(Locale.ROOT))));
        if (!passwords.matches(request.getPassword(), account == null ? null : account.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "账号或密码错误");
        }
        return issuePair(account);
    }

    public UserVO me(long userId) {
        var account = users.selectOneById(userId);
        if (account == null) { throw JwtTokenService.unauthorized(); }
        return UserVO.from(account);
    }

    @Transactional
    public TokenVO refresh(String rawToken) {
        var existing = refreshTokens.selectOneByQuery(QueryWrapper.create()
            .where(column("token_hash").eq(hashToken(rawToken))));
        if (existing == null) { throw invalidRefresh(); }
        LocalDateTime now = now();
        var consumed = new RefreshToken();
        consumed.setRevokedAt(now);
        // Conditional UPDATE takes a row lock and rechecks the predicate after waiting.
        // Rotation and issuing the replacement are committed or rolled back together.
        int updated = refreshTokens.updateByQuery(consumed, QueryWrapper.create()
            .where(column("id").eq(existing.getId()))
            .and(column("revoked_at").isNull()).and(column("expires_at").gt(now)));
        if (updated != 1) { throw invalidRefresh(); }
        var account = users.selectOneById(existing.getUserId());
        if (account == null) { throw invalidRefresh(); }
        return issuePair(account);
    }

    @Transactional
    public void logout(String rawToken) {
        var revoked = new RefreshToken();
        revoked.setRevokedAt(now());
        // A raw Refresh Token authorizes revocation of that record only. Unknown and
        // already revoked values are idempotent; an Access Token is neither needed nor used.
        refreshTokens.updateByQuery(revoked, QueryWrapper.create()
            .where(column("token_hash").eq(hashToken(rawToken))).and(column("revoked_at").isNull()));
    }

    private TokenVO issuePair(UserAccount account) {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = now();
        var refresh = new RefreshToken();
        refresh.setUserId(account.getId());
        refresh.setTokenHash(hashToken(raw));
        refresh.setExpiresAt(now.plus(refreshTtl));
        refresh.setCreatedAt(now);
        refreshTokens.insertSelective(refresh);
        return new TokenVO(jwt.issue(account.getId()), raw, "Bearer", jwt.expiresIn(), UserVO.from(account));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private BusinessException invalidRefresh() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh Token 无效或已过期");
    }

    private BusinessException duplicate() {
        return new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
    }
}
