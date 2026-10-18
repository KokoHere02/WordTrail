package com.wordtrail;

import com.fasterxml.jackson.databind.*;
import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.security.PasswordService;
import com.wordtrail.user.mapper.*;
import com.wordtrail.user.service.UserService;
import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.mybatisflex.core.query.QueryMethods.column;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthIntegrationTests.TestTime.class)
class AuthIntegrationTests {
    private static final String SECRET = Base64.getEncoder().encodeToString(Jwts.SIG.HS256.key().build().getEncoded());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> SECRET);
    }

    static class TestClock extends Clock {
        private Instant now = Instant.now();
        void reset() { now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS); }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration static class TestTime {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }

    private final Environment environment;
    private final ObjectMapper json;
    private final UserMapper users;
    private final RefreshTokenMapper refreshTokens;
    private final PasswordService passwords;
    private final TestClock clock;
    private final UserService service;
    private final PlatformTransactionManager transactions;
    private final HttpClient client = HttpClient.newHttpClient();
    private final Set<Long> createdUsers = ConcurrentHashMap.newKeySet();

    @Autowired
    AuthIntegrationTests(Environment environment, ObjectMapper json, UserMapper users,
            RefreshTokenMapper refreshTokens, PasswordService passwords, TestClock clock,
            UserService service, PlatformTransactionManager transactions) {
        this.environment = environment;
        this.json = json;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.clock = clock;
        this.service = service;
        this.transactions = transactions;
    }

    @BeforeEach void resetTime() { clock.reset(); }

    @AfterEach void cleanup() {
        for (Long id : createdUsers) {
            refreshTokens.deleteByQuery(QueryWrapper.create().where(column("user_id").eq(id)));
            users.deleteById(id);
        }
        createdUsers.clear();
    }

    private String name() { return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }
    private String url(String path) {
        return "http://localhost:" + environment.getRequiredProperty("local.server.port") + path;
    }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url(path)));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        // Tokens are deliberately returned by login/refresh; password material never is.
        assertFalse(response.body().contains("password_hash"));
        assertFalse(response.body().contains("passwordHash"));
        assertFalse(response.body().contains("$2a$"));
        assertTrue(response.headers().allValues("set-cookie").isEmpty());
        if (path.equals("/api/auth/register") && response.statusCode() == 201) {
            createdUsers.add(json.readTree(response.body()).at("/data/id").asLong());
        }
        return response;
    }
    private HttpResponse<String> register(String username, String password) throws Exception {
        return call("POST", "/api/auth/register",
            Map.of("username", username, "password", password, "nickname", "昵称"), null);
    }
    private JsonNode pair(String username, String password) throws Exception {
        var response = call("POST", "/api/auth/login", Map.of("username", username, "password", password), null);
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).get("data");
    }
    private HttpResponse<String> refresh(String raw) throws Exception {
        // A deliberately invalid Access Token must not block the public refresh endpoint.
        return call("POST", "/api/auth/refresh", Map.of("refreshToken", raw), "invalid-access-token");
    }
    private String hash(String raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void registerLoginAndMeWithoutCookieOrCsrf() throws Exception {
        String username = name();
        var registered = register(username, " password ");
        assertEquals(201, registered.statusCode());
        assertEquals(401, call("GET", "/api/auth/me", null, null).statusCode());
        var tokens = pair(username.toUpperCase(Locale.ROOT), " password ");
        assertEquals("Bearer", tokens.get("tokenType").asText());
        assertEquals(1800, tokens.get("expiresIn").asLong());
        String access = tokens.get("accessToken").asText();
        String refresh = tokens.get("refreshToken").asText();
        assertEquals(86, refresh.length());
        var me = call("GET", "/api/auth/me?user_id=99999", null, access);
        assertEquals(200, me.statusCode());
        var user = json.readTree(me.body()).get("data");
        assertEquals(username, user.get("username").asText());
        assertEquals("昵称", user.get("nickname").asText());
        assertEquals(3, user.size());
        assertEquals(200, call("GET", "/api/auth/me", null, access).statusCode());
        assertEquals(401, call("GET", "/api/auth/me", null, null).statusCode());
        var stored = refreshTokens.selectOneByQuery(QueryWrapper.create().where(column("token_hash").eq(hash(refresh))));
        assertNotNull(stored);
        assertEquals(64, stored.getTokenHash().length());
        assertNotEquals(refresh, stored.getTokenHash());
        assertEquals(Duration.ofDays(7), Duration.between(stored.getCreatedAt(), stored.getExpiresAt()));
        assertTrue(passwords.matches(" password ", users.selectOneById(user.get("id").asLong()).getPasswordHash()));
        // Legacy CSRF endpoint is no longer public or implemented.
        assertEquals(401, call("GET", "/api/auth/csrf", null, null).statusCode());
    }

    @Test void duplicateAndInvalidRegistration() throws Exception {
        String username = name();
        assertEquals(201, register(username, "password123").statusCode());
        var duplicate = register(username.toUpperCase(Locale.ROOT), "password456");
        assertEquals(409, duplicate.statusCode());
        assertEquals("USERNAME_EXISTS", json.readTree(duplicate.body()).get("code").asText());
        for (String invalid : List.of("abc", "a".repeat(33), "has space", "中文用户名")) {
            assertEquals(400, register(invalid, "password123").statusCode());
        }
        for (String password : List.of("", "short", "x".repeat(65))) {
            var response = register(name(), password);
            assertEquals(400, response.statusCode());
            if (!password.isEmpty()) { assertFalse(response.body().contains(password)); }
        }
        assertEquals(400, call("POST", "/api/auth/register", Map.of(), null).statusCode());
        assertEquals(400, call("POST", "/api/auth/register",
            Map.of("username", name(), "password", "password123", "nickname", "n".repeat(65)), null).statusCode());
        assertEquals(201, register("a".repeat(10) + name(), "x".repeat(64)).statusCode());
    }

    @Test void loginFailureIsUniformAndDoesNotTrimPasswords() throws Exception {
        String username = name();
        assertEquals(201, register(username, " password ").statusCode());
        var wrong = call("POST", "/api/auth/login", Map.of("username", username, "password", "password"), null);
        var missing = call("POST", "/api/auth/login", Map.of("username", name(), "password", "password"), null);
        assertEquals(401, wrong.statusCode());
        assertEquals(wrong.body(), missing.body());
        assertEquals("账号或密码错误", json.readTree(wrong.body()).get("message").asText());
        assertNotNull(pair(username, " password ").get("accessToken"));
    }

    @Test void unicodePasswordsRemainDistinctBeyondBcryptByteLimit() throws Exception {
        String username = name(), password = "密".repeat(63) + "码";
        assertEquals(201, register(username, password).statusCode());
        assertEquals(401, call("POST", "/api/auth/login",
            Map.of("username", username, "password", "密".repeat(63) + "错"), null).statusCode());
        assertNotNull(pair(username, password).get("accessToken"));
    }

    @Test void missingMalformedTamperedAndExpiredAccessTokensReturnSame401() throws Exception {
        String username = name();
        assertEquals(201, register(username, "password123").statusCode());
        var pair = pair(username, "password123");
        String access = pair.get("accessToken").asText();
        String[] parts = access.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'a' ? 'b' : 'a') + parts[2].substring(1);
        var missing = call("GET", "/api/auth/me", null, null);
        assertEquals(401, missing.statusCode());
        for (String token : List.of("", "not-a-token", "a.b.c", tampered)) {
            var invalid = call("GET", "/api/auth/me", null, token);
            assertEquals(401, invalid.statusCode());
            assertEquals(missing.body(), invalid.body());
        }
        clock.advance(Duration.ofMinutes(31));
        var expired = call("GET", "/api/auth/me", null, access);
        assertEquals(401, expired.statusCode());
        assertEquals(missing.body(), expired.body());
        // Refresh does not depend on the expired Access Token.
        assertEquals(200, refresh(pair.get("refreshToken").asText()).statusCode());
    }

    @Test void refreshRotatesAndOldValueCannotBeReused() throws Exception {
        String username = name();
        assertEquals(201, register(username, "password123").statusCode());
        var first = pair(username, "password123");
        String old = first.get("refreshToken").asText();
        var response = refresh(old);
        assertEquals(200, response.statusCode());
        var next = json.readTree(response.body()).get("data");
        assertNotEquals(old, next.get("refreshToken").asText());
        assertNotEquals(first.get("accessToken").asText(), next.get("accessToken").asText());
        assertEquals(401, refresh(old).statusCode());
        assertEquals(200, call("GET", "/api/auth/me", null, next.get("accessToken").asText()).statusCode());
        assertNotNull(refreshTokens.selectOneByQuery(QueryWrapper.create()
            .where(column("token_hash").eq(hash(old)))).getRevokedAt());
    }

    @Test void concurrentRefreshHasExactlyOneWinner() throws Exception {
        String username = name();
        assertEquals(201, register(username, "password123").statusCode());
        String raw = pair(username, "password123").get("refreshToken").asText();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return refresh(raw).statusCode(); });
            var b = pool.submit(() -> { gate.await(); return refresh(raw).statusCode(); });
            gate.countDown();
            assertEquals(Set.of(200, 401), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(1, refreshTokens.selectCountByQuery(QueryWrapper.create()
            .where(column("user_id").eq(createdUsers.iterator().next())).and(column("revoked_at").isNull())));
    }

    @Test void failedTransactionRestoresOldRefreshToken() throws Exception {
        String username = name();
        assertEquals(201, register(username, "password123").statusCode());
        String raw = pair(username, "password123").get("refreshToken").asText();
        var transaction = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            service.refresh(raw);
            throw new IllegalStateException("simulated failure after rotation");
        }));
        assertEquals(1, refreshTokens.selectCountByQuery(QueryWrapper.create()
            .where(column("user_id").eq(createdUsers.iterator().next()))));
        assertEquals(200, refresh(raw).statusCode());
    }

    @Test void expiredRefreshCannotBeUsed() throws Exception {
        String username = name();
        assertEquals(201, register(username, "password123").statusCode());
        String raw = pair(username, "password123").get("refreshToken").asText();
        clock.advance(Duration.ofDays(8));
        assertEquals(401, refresh(raw).statusCode());
        assertEquals(401, refresh("unknown-refresh-token").statusCode());
        assertEquals(400, call("POST", "/api/auth/refresh", Map.of(), null).statusCode());
    }

    @Test void logoutIsIdempotentAndOnlyAffectsSubmittedDevice() throws Exception {
        String username = name();
        assertEquals(201, register(username, "password123").statusCode());
        var first = pair(username, "password123");
        var second = pair(username, "password123");
        assertNotEquals(first.get("refreshToken").asText(), second.get("refreshToken").asText());
        var body = Map.of("refreshToken", first.get("refreshToken").asText());
        assertEquals(200, call("POST", "/api/auth/logout", body, "invalid-access-token").statusCode());
        assertEquals(200, call("POST", "/api/auth/logout", body, null).statusCode());
        assertEquals(200, call("POST", "/api/auth/logout", Map.of("refreshToken", "unknown"), null).statusCode());
        assertEquals(401, refresh(first.get("refreshToken").asText()).statusCode());
        assertEquals(200, refresh(second.get("refreshToken").asText()).statusCode());
        // Accepted first-version limitation: logout cannot revoke already issued Access Tokens.
        assertEquals(200, call("GET", "/api/auth/me", null, first.get("accessToken").asText()).statusCode());
    }

    @Test void accountsAndSequentialRequestsHaveIndependentIdentities() throws Exception {
        String first = name(), second = name();
        assertEquals(201, register(first, "password123").statusCode());
        assertEquals(201, register(second, "password123").statusCode());
        String a = pair(first, "password123").get("accessToken").asText();
        String b = pair(second, "password123").get("accessToken").asText();
        for (String token : List.of(a, b, a)) {
            var me = call("GET", "/api/auth/me", null, token);
            assertEquals(token.equals(a) ? first : second, json.readTree(me.body()).at("/data/username").asText());
            assertEquals(401, call("GET", "/api/auth/me", null, null).statusCode());
        }
    }

    @Test void corsAllowsOnlyConfiguredOriginsAndAuthorizationHeader() throws Exception {
        for (String origin : List.of("http://localhost:5173", "https://untrusted.example")) {
            var request = HttpRequest.newBuilder(URI.create(url("/api/auth/me")))
                .header("Origin", origin).header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (origin.equals("http://localhost:5173")) {
                assertEquals(200, response.statusCode());
                assertEquals(origin, response.headers().firstValue("access-control-allow-origin").orElseThrow());
                assertTrue(response.headers().firstValue("access-control-allow-headers").orElseThrow()
                    .toLowerCase(Locale.ROOT).contains("authorization"));
                assertTrue(response.headers().firstValue("access-control-allow-credentials").isEmpty());
            } else {
                assertEquals(403, response.statusCode());
                assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty());
            }
        }
    }
}
