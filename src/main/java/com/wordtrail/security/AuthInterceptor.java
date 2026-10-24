package com.wordtrail.security;

import jakarta.servlet.http.*;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements AsyncHandlerInterceptor {
    private static final Set<String> PUBLIC_POSTS = Set.of(
        "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout");
    private final JwtTokenService tokens;
    private final CurrentUser currentUser;
    private final AuthSessionGuard sessions;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        currentUser.clear();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (CorsUtils.isPreFlightRequest(request)
                || ("POST".equals(request.getMethod()) && PUBLIC_POSTS.contains(path))
                || ("GET".equals(request.getMethod())
                    && (path.equals("/favicon.ico") || path.startsWith("/assets/")))) {
            return true;
        }
        var headers = request.getHeaders("Authorization");
        String header = headers.hasMoreElements() ? headers.nextElement() : null;
        if (header == null || headers.hasMoreElements() || header.length() > 4096
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw JwtTokenService.unauthorized();
        }
        String token = header.substring(7);
        if (!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw JwtTokenService.unauthorized();
        }
        var identity=tokens.identity(token);
        sessions.require(identity);
        currentUser.bind(identity.userId(),identity.sessionId(),identity.authVersion());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        currentUser.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        currentUser.clear();
    }
}
