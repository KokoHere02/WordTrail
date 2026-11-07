package com.wordtrail;

import tools.jackson.databind.*;
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
@Import({com.wordtrail.H2TestSchema.class, AuthIntegrationTests.TestTime.class})
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
        @Bean AuthFailureProbe authFailureProbe(){return new AuthFailureProbe();}
        @Bean @Primary com.wordtrail.security.SmsGateway fakeSmsGateway() { return new com.wordtrail.security.SmsGateway() {
            public void send(String phone, String purpose) {}
            public boolean verify(String phone, String code) { return "123456".equals(code); }
        }; }
    }
    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}))
    static class AuthFailureProbe implements org.apache.ibatis.plugin.Interceptor {
        volatile String failAfterMapper;
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            Object result=invocation.proceed();String id=((org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0]).getId();
            if(failAfterMapper!=null && id.startsWith(failAfterMapper+".")) throw new IllegalStateException("Injected authentication write failure");return result;
        }
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

    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private AuthSessionMapper authSessions;
    @Autowired private com.wordtrail.security.JwtTokenService jwt;
    @Autowired private AuthFailureProbe authFailure;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate db;

    @BeforeEach void resetTime() throws Exception {
        IsolatedDatabaseGuard.check(dataSource);
        clock.reset();
        db.update("DELETE FROM sms_challenge"); db.update("DELETE FROM sms_send_log"); db.update("DELETE FROM auth_password_attempt");
        authFailure.failAfterMapper=null;
    }

    @AfterEach void cleanup() {
        authFailure.failAfterMapper=null;
        for (Long id : createdUsers) {
            refreshTokens.deleteByQuery(QueryWrapper.create().where(column("user_id").eq(id)));
            authSessions.deleteByQuery(QueryWrapper.create().where(column("user_id").eq(id)));
            users.deleteById(id);
        }
        createdUsers.clear();
    }

    private String name() { return "1" + (3 + java.util.concurrent.ThreadLocalRandom.current().nextInt(7)) + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000)); }
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
        return response;
    }
    private HttpResponse<String> register(String phone, String password) throws Exception {
        db.update("DELETE FROM sms_send_log");
        assertEquals(200, call("POST", "/api/auth/phone/send", Map.of("phone", phone, "purpose", "REGISTER"), null).statusCode());
        var response = call("POST", "/api/auth/phone/register", Map.of("phone", phone, "code", "123456", "password", password), null);
        if (response.statusCode() == 200) {
            long id=json.readTree(response.body()).at("/data/user/id").asLong();
            createdUsers.add(id);
            refreshTokens.deleteByQuery(QueryWrapper.create().where(column("user_id").eq(id)));
            authSessions.deleteByQuery(QueryWrapper.create().where(column("user_id").eq(id)));
        }
        return response;
    }
    private JsonNode pair(String phone, String password) throws Exception {
        var response = call("POST", "/api/auth/phone/password-login", Map.of("phone", phone, "password", password), null);
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).get("data");
    }
    private void sendPasswordCode(String phone, String access) throws Exception {
        db.update("DELETE FROM sms_send_log");
        assertEquals(200, call("POST", "/api/auth/phone/send-auth", Map.of("phone", phone, "purpose", "PASSWORD"), access).statusCode());
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
        assertEquals(200, registered.statusCode());
        assertEquals(401, call("GET", "/api/auth/me", null, null).statusCode());
        var tokens = pair(username, " password ");
        assertEquals("Bearer", tokens.get("tokenType").asText());
        assertEquals(1800, tokens.get("expiresIn").asLong());
        String access = tokens.get("accessToken").asText();
        String refresh = tokens.get("refreshToken").asText();
        assertEquals(86, refresh.length());
        var me = call("GET", "/api/auth/me?user_id=99999", null, access);
        assertEquals(200, me.statusCode());
        var user = json.readTree(me.body()).get("data");
        assertEquals(username, user.get("phone").asText());
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
        String phone=name();
        assertEquals(200, register(phone, "password123").statusCode());
        var duplicate=register(phone,"password456");
        assertEquals(409,duplicate.statusCode());
        assertEquals("PHONE_EXISTS",json.readTree(duplicate.body()).get("code").asText());
        assertEquals(400,call("POST","/api/auth/phone/register",Map.of(),null).statusCode());
        for (String invalid : List.of("abc", "123", "123456789012"))
            assertEquals(400,call("POST","/api/auth/phone/register",Map.of("phone",invalid,"code","123456","password","password123"),null).statusCode());
        for (String password : List.of("", "short", "x".repeat(65)))
            assertEquals(400,call("POST","/api/auth/phone/register",Map.of("phone",name(),"code","123456","password",password),null).statusCode());
    }

    @Test void loginFailureIsUniformAndDoesNotTrimPasswords() throws Exception {
        String username = name();
        assertEquals(200, register(username, " password ").statusCode());
        var wrong = call("POST", "/api/auth/phone/password-login", Map.of("phone", username, "password", "password"), null);
        var missing = call("POST", "/api/auth/phone/password-login", Map.of("phone", name(), "password", "password"), null);
        assertEquals(401, wrong.statusCode());
        assertEquals(wrong.body(), missing.body());
        assertEquals("账号或密码错误", json.readTree(wrong.body()).get("message").asText());
        assertNotNull(pair(username, " password ").get("accessToken"));
    }

    @Test void unicodePasswordsRemainDistinctBeyondBcryptByteLimit() throws Exception {
        String username = name(), password = "密".repeat(63) + "码";
        assertEquals(200, register(username, password).statusCode());
        assertEquals(401, call("POST", "/api/auth/phone/password-login",
            Map.of("phone", username, "password", "密".repeat(63) + "错"), null).statusCode());
        assertNotNull(pair(username, password).get("accessToken"));
    }

    @Test void missingMalformedTamperedAndExpiredAccessTokensReturnSame401() throws Exception {
        String username = name();
        assertEquals(200, register(username, "password123").statusCode());
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
        assertEquals(200, register(username, "password123").statusCode());
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
        assertEquals(200, register(username, "password123").statusCode());
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
        assertEquals(200, register(username, "password123").statusCode());
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
        assertEquals(200, register(username, "password123").statusCode());
        String raw = pair(username, "password123").get("refreshToken").asText();
        clock.advance(Duration.ofDays(8));
        assertEquals(401, refresh(raw).statusCode());
        assertEquals(401, refresh("unknown-refresh-token").statusCode());
        assertEquals(400, call("POST", "/api/auth/refresh", Map.of(), null).statusCode());
    }

    @Test void logoutIsIdempotentAndOnlyAffectsSubmittedDevice() throws Exception {
        String username = name();
        assertEquals(200, register(username, "password123").statusCode());
        var first = pair(username, "password123");
        var second = pair(username, "password123");
        assertNotEquals(first.get("refreshToken").asText(), second.get("refreshToken").asText());
        var body = Map.of("refreshToken", first.get("refreshToken").asText());
        assertEquals(200, call("POST", "/api/auth/logout", body, "invalid-access-token").statusCode());
        assertEquals(200, call("POST", "/api/auth/logout", body, null).statusCode());
        assertEquals(200, call("POST", "/api/auth/logout", Map.of("refreshToken", "unknown"), null).statusCode());
        assertEquals(401, refresh(first.get("refreshToken").asText()).statusCode());
        assertEquals(200, refresh(second.get("refreshToken").asText()).statusCode());
        assertEquals(401, call("GET", "/api/auth/me", null, first.get("accessToken").asText()).statusCode());
    }

    @Test void accountsAndSequentialRequestsHaveIndependentIdentities() throws Exception {
        String first = name(), second = name();
        assertEquals(200, register(first, "password123").statusCode());
        assertEquals(200, register(second, "password123").statusCode());
        String a = pair(first, "password123").get("accessToken").asText();
        String b = pair(second, "password123").get("accessToken").asText();
        for (String token : List.of(a, b, a)) {
            var me = call("GET", "/api/auth/me", null, token);
            assertEquals(token.equals(a) ? first : second, json.readTree(me.body()).at("/data/phone").asText());
            assertEquals(401, call("GET", "/api/auth/me", null, null).statusCode());
        }
    }

    private JsonNode sessionPage(String access) throws Exception {var response=call("GET","/api/auth/sessions",null,access);assertEquals(200,response.statusCode());return json.readTree(response.body()).get("data");}
    private String currentSession(String access) throws Exception {for(var row:sessionPage(access).get("records"))if(row.get("current").asBoolean())return row.get("sessionId").asString();throw new AssertionError("Current session not listed");}

    @Test void deviceSessionsListCurrentDeviceAndAllowOnlyOwnSessionRevocation() throws Exception {
        String username=name(),other=name();register(username,"password123");register(other,"password123");
        var first=pair(username,"password123");var second=pair(username,"password123");var foreign=pair(other,"password123");String a=first.get("accessToken").asString(),b=second.get("accessToken").asString();
        assertEquals(2,sessionPage(a).get("total").asLong());String foreignId=currentSession(foreign.get("accessToken").asString());
        assertEquals(404,call("POST","/api/auth/sessions/"+foreignId+"/revoke",null,a).statusCode());
        String target=currentSession(b);assertEquals(200,call("POST","/api/auth/sessions/"+target+"/revoke",null,a).statusCode());assertEquals(200,call("POST","/api/auth/sessions/"+target+"/revoke",null,a).statusCode());
        assertEquals(401,call("GET","/api/auth/me",null,b).statusCode());assertEquals(401,refresh(second.get("refreshToken").asString()).statusCode());assertEquals(200,call("GET","/api/auth/me",null,a).statusCode());assertEquals(1,sessionPage(a).get("total").asLong());
    }

    @Test void logoutOtherDevicesRotatesCurrentPairAndImmediatelyInvalidatesOldAccessAndRefreshTokens() throws Exception {
        String username=name();register(username,"password123");var first=pair(username,"password123");var second=pair(username,"password123");String access=first.get("accessToken").asString();
        assertEquals(401,call("POST","/api/auth/logout-others",Map.of("currentPassword","wrong-password"),access).statusCode());assertEquals(2,sessionPage(access).get("total").asLong());
        var response=call("POST","/api/auth/logout-others",Map.of("currentPassword","password123"),access);assertEquals(200,response.statusCode());var next=json.readTree(response.body()).get("data");
        for(var previous:List.of(first,second)){assertEquals(401,call("GET","/api/auth/me",null,previous.get("accessToken").asString()).statusCode());assertEquals(401,refresh(previous.get("refreshToken").asString()).statusCode());}
        assertEquals(200,call("GET","/api/auth/me",null,next.get("accessToken").asString()).statusCode());assertEquals(1,sessionPage(next.get("accessToken").asString()).get("total").asLong());assertEquals(200,refresh(next.get("refreshToken").asString()).statusCode());
    }

    @Test void changingPasswordPreservesUnicodeAndSpacesAndRevokesEveryDeviceAndLegacyAccess() throws Exception {
        String username=name(),old=" password123 ",changed=" 新密码"+"密".repeat(28)+" ";register(username,old);var first=pair(username,old);var second=pair(username,old);long id=first.get("user").get("id").asLong();String legacy=jwt.issue(id);
        sendPasswordCode(username,first.get("accessToken").asString());
        assertEquals(200,call("PUT","/api/auth/phone/password",Map.of("code","123456","newPassword",changed),first.get("accessToken").asString()).statusCode());
        for(String access:List.of(first.get("accessToken").asString(),second.get("accessToken").asString(),legacy))assertEquals(401,call("GET","/api/auth/me",null,access).statusCode());
        for(var previous:List.of(first,second))assertEquals(401,refresh(previous.get("refreshToken").asString()).statusCode());
        assertEquals(401,call("POST","/api/auth/phone/password-login",Map.of("phone",username,"password",old),null).statusCode());
        assertEquals(401,call("POST","/api/auth/phone/password-login",Map.of("phone",username,"password",changed.strip()),null).statusCode());
        var next=pair(username,changed);assertEquals(id,next.get("user").get("id").asLong());assertEquals(200,call("GET","/api/auth/me",null,next.get("accessToken").asString()).statusCode());
    }

    @Test void invalidPasswordChangesLeaveCredentialsAndSessionsUntouched() throws Exception {
        String phone=name(); register(phone,"password123"); var tokens=pair(phone,"password123"); String access=tokens.get("accessToken").asString();
        sendPasswordCode(phone,access);
        assertEquals(400,call("PUT","/api/auth/phone/password",Map.of("code","000000","newPassword","new-password"),access).statusCode());
        assertEquals(400,call("PUT","/api/auth/phone/password",Map.of("code","123456","newPassword","short"),access).statusCode());
        assertEquals(200,call("GET","/api/auth/me",null,access).statusCode());
        assertEquals(200,refresh(tokens.get("refreshToken").asString()).statusCode());
    }

    @Test void logoutWithConsumedRefreshDoesNotTerminateItsReplacementSession() throws Exception {
        String username=name();register(username,"password123");var first=pair(username,"password123");var response=refresh(first.get("refreshToken").asString());var next=json.readTree(response.body()).get("data");
        assertEquals(200,call("POST","/api/auth/logout",Map.of("refreshToken",first.get("refreshToken").asString()),null).statusCode());assertEquals(200,call("GET","/api/auth/me",null,next.get("accessToken").asString()).statusCode());
        assertEquals(200,call("POST","/api/auth/logout",Map.of("refreshToken",next.get("refreshToken").asString()),null).statusCode());assertEquals(401,call("GET","/api/auth/me",null,next.get("accessToken").asString()).statusCode());
    }

    @Test void legacyRefreshUpgradesToSessionAndGlobalRevocationInvalidatesLegacyAccess() throws Exception {
        String username=name();register(username,"password123");long id=createdUsers.iterator().next();String legacy=jwt.issue(id),raw="legacy-"+UUID.randomUUID();
        var row=new com.wordtrail.user.entity.RefreshToken();row.setUserId(id);row.setTokenHash(hash(raw));row.setCreatedAt(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));row.setExpiresAt(row.getCreatedAt().plusDays(7));refreshTokens.insertSelective(row);
        assertEquals(200,call("GET","/api/auth/me",null,legacy).statusCode());assertEquals(409,call("POST","/api/auth/logout-others",Map.of("currentPassword","password123"),legacy).statusCode());
        var refreshed=refresh(raw);assertEquals(200,refreshed.statusCode());var next=json.readTree(refreshed.body()).get("data");assertNotNull(currentSession(next.get("accessToken").asString()));
        assertEquals(200,call("POST","/api/auth/logout-others",Map.of("currentPassword","password123"),next.get("accessToken").asString()).statusCode());assertEquals(401,call("GET","/api/auth/me",null,legacy).statusCode());
    }

    @Test void passwordAndLogoutOtherDeviceFailuresRollBackVersionHashesAndRevocations() throws Exception {
        String username=name();register(username,"password123");var first=pair(username,"password123");var second=pair(username,"password123");String access=first.get("accessToken").asString();
        sendPasswordCode(username,access);
        authFailure.failAfterMapper="com.wordtrail.user.mapper.AuthSessionMapper";
        try{assertEquals(500,call("PUT","/api/auth/phone/password",Map.of("code","123456","newPassword","changed-password"),access).statusCode());}finally{authFailure.failAfterMapper=null;}
        assertEquals(200,call("GET","/api/auth/me",null,access).statusCode());assertEquals(0,users.selectOneById(first.get("user").get("id").asLong()).getAuthVersion());
        assertEquals(2,sessionPage(access).get("total").asLong());assertTrue(passwords.matches("password123",users.selectOneById(first.get("user").get("id").asLong()).getPasswordHash()));
        authFailure.failAfterMapper="com.wordtrail.user.mapper.RefreshTokenMapper";
        try{assertEquals(500,call("POST","/api/auth/logout-others",Map.of("currentPassword","password123"),access).statusCode());}finally{authFailure.failAfterMapper=null;}
        assertEquals(200,refresh(first.get("refreshToken").asString()).statusCode());assertEquals(200,refresh(second.get("refreshToken").asString()).statusCode());
    }

    @Test void racingRefreshCannotRestoreADeviceRevokedByLogoutOthers() throws Exception {
        String username=name();register(username,"password123");var first=pair(username,"password123");var second=pair(username,"password123");
        try(var pool=Executors.newFixedThreadPool(2)){
            var refreshTask=pool.submit(()->refresh(second.get("refreshToken").asString()));
            var revokeTask=pool.submit(()->call("POST","/api/auth/logout-others",Map.of("currentPassword","password123"),first.get("accessToken").asString()));
            var refreshResponse=refreshTask.get();var revoked=revokeTask.get();assertEquals(200,revoked.statusCode());assertTrue(refreshResponse.statusCode()==200||refreshResponse.statusCode()==401);
            if(refreshResponse.statusCode()==200)assertEquals(401,call("GET","/api/auth/me",null,json.readTree(refreshResponse.body()).get("data").get("accessToken").asString()).statusCode());
            String next=json.readTree(revoked.body()).get("data").get("accessToken").asString();assertEquals(1,sessionPage(next).get("total").asLong());
        }
    }

    @Test void deviceLabelsAreOptionalBoundedAndNeverReplaceServerAuthorization() throws Exception {
        String username=name();register(username,"password123");var response=call("POST","/api/auth/phone/password-login",Map.of("phone",username,"password","password123","deviceName","我的浏览器"),null);assertEquals(200,response.statusCode());String access=json.readTree(response.body()).get("data").get("accessToken").asString();
        assertEquals("我的浏览器",sessionPage(access).get("records").get(0).get("deviceName").asString());
        assertEquals(400,call("POST","/api/auth/phone/password-login",Map.of("phone",username,"password","password123","deviceName","x".repeat(101)),null).statusCode());
        assertEquals(400,call("POST","/api/auth/sessions/not-an-id/revoke",null,access).statusCode());
    }

    @Test void corsAllowsOnlyConfiguredOriginsAndAuthorizationHeader() throws Exception {
        for (String origin : List.of("http://localhost:5173", "http://127.0.0.1:5173", "https://untrusted.example")) {
            for (String method : List.of("GET", "POST")) {
                String path = method.equals("POST") ? "/api/auth/phone/password-login" : "/api/auth/me";
                String header = method.equals("POST") ? "content-type" : "authorization";
                var request = HttpRequest.newBuilder(URI.create(url(path)))
                    .header("Origin", origin).header("Access-Control-Request-Method", method)
                    .header("Access-Control-Request-Headers", header)
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (!origin.equals("https://untrusted.example")) {
                    assertEquals(200, response.statusCode());
                    assertEquals(origin, response.headers().firstValue("access-control-allow-origin").orElseThrow());
                    assertTrue(response.headers().firstValue("access-control-allow-headers").orElseThrow()
                        .toLowerCase(Locale.ROOT).contains(header));
                    assertTrue(response.headers().firstValue("access-control-allow-credentials").isEmpty());
                } else {
                    assertEquals(403, response.statusCode());
                    assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty());
                }
            }
            if (!origin.equals("https://untrusted.example")) {
                var login = HttpRequest.newBuilder(URI.create(url("/api/auth/phone/password-login")))
                    .header("Origin", origin).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"phone\":\"invalid\",\"password\":\"short\"}"))
                    .build();
                var response = client.send(login, HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode());
                assertEquals("INVALID_PARAMETER", json.readTree(response.body()).get("code").asText());
                assertEquals(origin, response.headers().firstValue("access-control-allow-origin").orElseThrow());
            }
        }
    }
}
