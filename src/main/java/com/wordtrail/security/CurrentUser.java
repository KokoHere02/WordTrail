package com.wordtrail.security;

import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    private record Principal(long id,String sessionId,long authVersion) {}
    private static final ThreadLocal<Principal> PRINCIPAL = new ThreadLocal<>();

    public long id() {
        var principal=PRINCIPAL.get();
        if(principal==null) throw JwtTokenService.unauthorized();
        return principal.id();
    }

    // Only the interceptor may bind a verified identity.
    public String sessionId(){id();return PRINCIPAL.get().sessionId();}
    public long authVersion(){id();return PRINCIPAL.get().authVersion();}
    void bind(long userId) { bind(userId,null,0); }
    void bind(long userId,String sessionId,long authVersion){PRINCIPAL.set(new Principal(userId,sessionId,authVersion));}
    void clear() { PRINCIPAL.remove(); }
}
