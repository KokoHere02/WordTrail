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
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.WordHistoryQuery;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service
public class UserService {
    private final UserMapper users;
    private final RefreshTokenMapper refreshTokens;
    private final AuthSessionMapper sessions;
    private final CurrentUser current;
    private final PasswordService passwords;
    private final JwtTokenService jwt;
    private final Clock clock;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserMapper users, RefreshTokenMapper refreshTokens, PasswordService passwords,
            JwtTokenService jwt, Clock clock, @Value("${app.refresh-token.ttl:7d}") Duration refreshTtl,
            AuthSessionMapper sessions,CurrentUser current) {
        if (refreshTtl == null || refreshTtl.toSeconds() < 1) {
            throw new IllegalStateException("Refresh Token TTL must be at least one second");
        }
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.jwt = jwt;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
        this.sessions=sessions;this.current=current;
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
        account.setAuthVersion(0L);
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
            .where(column("username").eq(request.getUsername().toLowerCase(Locale.ROOT))).forUpdate());
        if (!passwords.matches(request.getPassword(), account == null ? null : account.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "账号或密码错误");
        }
        return issuePair(account,newSession(account,request.getDeviceName()));
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
        var account=lock(existing.getUserId());
        if(existing.getSessionId()==null && account.getAuthVersion()!=0) throw invalidRefresh();
        AuthSession session=existing.getSessionId()==null?null:sessions.selectOneById(existing.getSessionId());
        LocalDateTime now = now();
        if(existing.getSessionId()!=null && (session==null || !session.getUserId().equals(account.getId()) || session.getRevokedAt()!=null || !session.getExpiresAt().isAfter(now))) throw invalidRefresh();
        var consumed = new RefreshToken();
        consumed.setRevokedAt(now);
        // Conditional UPDATE takes a row lock and rechecks the predicate after waiting.
        // Rotation and issuing the replacement are committed or rolled back together.
        int updated = refreshTokens.updateByQuery(consumed, QueryWrapper.create()
            .where(column("id").eq(existing.getId()))
            .and(column("revoked_at").isNull()).and(column("expires_at").gt(now)));
        if (updated != 1) { throw invalidRefresh(); }
        return issuePair(account,session==null?newSession(account,null):session);
    }

    @Transactional
    public void logout(String rawToken) {
        var token=refreshTokens.selectOneByQuery(QueryWrapper.create().where(column("token_hash").eq(hashToken(rawToken))));
        if(token==null) return;
        lock(token.getUserId());
        var revoked = new RefreshToken();
        revoked.setRevokedAt(now());
        // A raw Refresh Token authorizes revocation of that record only. Unknown and
        // already revoked values are idempotent; an Access Token is neither needed nor used.
        int changed=refreshTokens.updateByQuery(revoked, QueryWrapper.create()
            .where(column("token_hash").eq(hashToken(rawToken))).and(column("revoked_at").isNull()));
        if(changed==1 && token.getSessionId()!=null) revokeSessionRows(token.getUserId(),token.getSessionId());
    }

    @Transactional(readOnly=true)
    public PageVO<AuthSessionVO> sessions(WordHistoryQuery query) {
        var page=sessions.paginate(query.getPage(),query.getSize(),QueryWrapper.create().where(column("user_id").eq(current.id()))
            .and(column("revoked_at").isNull()).and(column("expires_at").gt(now())).orderBy(column("last_refreshed_at").desc(),column("id").asc()));
        return new PageVO<>(query.getPage(),query.getSize(),page.getTotalRow(),page.getRecords().stream().map(s->new AuthSessionVO(s.getId(),s.getDeviceName(),s.getId().equals(current.sessionId()),s.getCreatedAt(),s.getLastRefreshedAt(),s.getExpiresAt())).toList());
    }
    @Transactional
    public void revokeSession(String sessionId) {
        var account=lock(current.id());requireIdentity(account);var session=sessions.selectOneById(sessionId);
        if(session==null || !session.getUserId().equals(account.getId())) throw new BusinessException(HttpStatus.NOT_FOUND,"AUTH_SESSION_NOT_FOUND","设备会话不存在");
        revokeSessionRows(account.getId(),sessionId);
    }
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        var account=lock(current.id());requireIdentity(account);requirePassword(request.currentPassword(),account);
        if(request.currentPassword().equals(request.newPassword())) throw new BusinessException(HttpStatus.CONFLICT,"PASSWORD_UNCHANGED","新密码不能与当前密码相同");
        var patch=new UserAccount();patch.setId(account.getId());patch.setPasswordHash(passwords.hash(request.newPassword()));patch.setAuthVersion(Math.addExact(account.getAuthVersion(),1));patch.setUpdatedAt(now());
        requireWrite(users.update(patch));revokeAll(account.getId(),null);
    }
    @Transactional
    public TokenVO logoutOthers(LogoutOthersRequest request) {
        var account=lock(current.id());requireIdentity(account);requirePassword(request.currentPassword(),account);
        if(current.sessionId()==null) throw new BusinessException(HttpStatus.CONFLICT,"AUTH_SESSION_UPGRADE_REQUIRED","请先刷新登录凭证或重新登录");
        var session=sessions.selectOneById(current.sessionId());
        if(session==null || session.getRevokedAt()!=null || !session.getExpiresAt().isAfter(now())) throw JwtTokenService.unauthorized();
        account.setAuthVersion(Math.addExact(account.getAuthVersion(),1));var patch=new UserAccount();patch.setId(account.getId());patch.setAuthVersion(account.getAuthVersion());requireWrite(users.update(patch));
        revokeAll(account.getId(),session.getId());return issuePair(account,session);
    }
    private void requireIdentity(UserAccount account){if(account.getAuthVersion()!=current.authVersion()) throw JwtTokenService.unauthorized();}
    private void requirePassword(String raw,UserAccount account){if(!passwords.matches(raw,account.getPasswordHash())) throw new BusinessException(HttpStatus.UNAUTHORIZED,"BAD_CREDENTIALS","账号或密码错误");}
    private UserAccount lock(long id){var account=users.selectOneByQuery(QueryWrapper.create().where(column("id").eq(id)).forUpdate());if(account==null)throw JwtTokenService.unauthorized();return account;}
    private void revokeSessionRows(long userId,String sessionId) {
        var patch=new AuthSession();patch.setRevokedAt(now());sessions.updateByQuery(patch,QueryWrapper.create().where(column("id").eq(sessionId)).and(column("user_id").eq(userId)).and(column("revoked_at").isNull()));
        var token=new RefreshToken();token.setRevokedAt(now());refreshTokens.updateByQuery(token,QueryWrapper.create().where(column("user_id").eq(userId)).and(column("session_id").eq(sessionId)).and(column("revoked_at").isNull()));
    }
    private void revokeAll(long userId,String keepSession) {
        var token=new RefreshToken();token.setRevokedAt(now());refreshTokens.updateByQuery(token,QueryWrapper.create().where(column("user_id").eq(userId)).and(column("revoked_at").isNull()));
        var where=QueryWrapper.create().where(column("user_id").eq(userId)).and(column("revoked_at").isNull());if(keepSession!=null) where.and(column("id").ne(keepSession));
        var session=new AuthSession();session.setRevokedAt(now());sessions.updateByQuery(session,where);
    }
    private AuthSession newSession(UserAccount account,String name) {
        var session=new AuthSession();session.setId(UUID.randomUUID().toString().replace("-",""));session.setUserId(account.getId());
        session.setDeviceName(name==null||name.isBlank()?"未命名设备":name.strip());session.setCreatedAt(now());session.setLastRefreshedAt(now());session.setExpiresAt(now().plus(refreshTtl));requireWrite(sessions.insert(session));return session;
    }
    private TokenVO issuePair(UserAccount account,AuthSession session) {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = now();
        var refresh = new RefreshToken();
        refresh.setUserId(account.getId());
        refresh.setSessionId(session.getId());
        refresh.setTokenHash(hashToken(raw));
        refresh.setExpiresAt(now.plus(refreshTtl));
        refresh.setCreatedAt(now);
        requireWrite(refreshTokens.insertSelective(refresh));
        session.setLastRefreshedAt(now);session.setExpiresAt(now.plus(refreshTtl));requireWrite(sessions.update(session));
        return new TokenVO(jwt.issue(account.getId(),account.getAuthVersion(),session.getId()), raw, "Bearer", jwt.expiresIn(), UserVO.from(account));
    }
    private static void requireWrite(int n){if(n!=1) throw new IllegalStateException("Could not persist authentication change");}

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
