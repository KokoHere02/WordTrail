package com.wordtrail.security;

import com.wordtrail.common.exception.BusinessException;
import java.time.*;
import java.util.Base64;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthInterceptorTests {
    private final CurrentUser current = new CurrentUser();
    private final JwtTokenService tokens = new JwtTokenService(
        Base64.getEncoder().encodeToString(io.jsonwebtoken.Jwts.SIG.HS256.key().build().getEncoded()),
        "wordtrail", Duration.ofMinutes(30), Clock.systemUTC());
    private final AuthInterceptor interceptor = new AuthInterceptor(tokens, current,org.mockito.Mockito.mock(AuthSessionGuard.class));
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach void cleanup() { current.clear(); }
    private MockHttpServletRequest authenticated() {
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer " + tokens.issue(42));
        return request;
    }

    @Test void clearsIdentityOnCompletionExceptionAndAsyncHandoff() throws Exception {
        var request = authenticated();
        assertTrue(interceptor.preHandle(request, response, this));
        assertEquals(42, current.id());
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertTrue(worker.submit(() -> {
                try { current.id(); return false; }
                catch (BusinessException ex) { return true; }
            }).get());
        }
        interceptor.afterCompletion(request, response, this, new RuntimeException("controller failure"));
        assertThrows(BusinessException.class, current::id);
        interceptor.preHandle(request, response, this);
        interceptor.afterConcurrentHandlingStarted(request, response, this);
        assertThrows(BusinessException.class, current::id);
    }

    @Test void reusedThreadHasNoIdentityAfterRejectedOrPublicRequest() {
        current.bind(42);
        assertThrows(BusinessException.class, () ->
            interceptor.preHandle(new MockHttpServletRequest("GET", "/api/auth/me"), response, this));
        assertThrows(BusinessException.class, current::id);
        current.bind(42);
        assertTrue(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/auth/login"), response, this));
        assertThrows(BusinessException.class, current::id);
    }

    @Test void rejectsDuplicateOrNonBearerHeadersAndCookieOnlyAuthentication() {
        var request = authenticated();
        request.addHeader("Authorization", "Bearer " + tokens.issue(43));
        assertThrows(BusinessException.class, () -> interceptor.preHandle(request, response, this));
        var cookieOnly = new MockHttpServletRequest("GET", "/api/auth/me");
        cookieOnly.setCookies(new jakarta.servlet.http.Cookie("JSESSIONID", "old-session"));
        assertThrows(BusinessException.class, () -> interceptor.preHandle(cookieOnly, response, this));
        var basic = new MockHttpServletRequest("GET", "/api/auth/me");
        basic.addHeader("Authorization", "Basic value");
        assertThrows(BusinessException.class, () -> interceptor.preHandle(basic, response, this));
    }
}
