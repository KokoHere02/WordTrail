package com.wordtrail.security;

import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public long id() {
        Long id = USER_ID.get();
        if (id == null) { throw JwtTokenService.unauthorized(); }
        return id;
    }

    // Only the interceptor may bind a verified identity.
    void bind(long userId) { USER_ID.set(userId); }
    void clear() { USER_ID.remove(); }
}
