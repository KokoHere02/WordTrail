package com.wordtrail.security;
import com.wordtrail.user.mapper.*;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class AuthSessionGuard {
    private final UserMapper users;
    private final AuthSessionMapper sessions;
    private final Clock clock;
    public void require(JwtTokenService.Identity identity) {
        var user=users.selectOneById(identity.userId());
        if(user==null || user.getAuthVersion()==null || user.getAuthVersion()!=identity.authVersion()) throw JwtTokenService.unauthorized();
        if(identity.sessionId()!=null) {
            var session=sessions.selectOneById(identity.sessionId());
            var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
            if(session==null || session.getUserId()!=identity.userId() || session.getRevokedAt()!=null || !session.getExpiresAt().isAfter(now)) throw JwtTokenService.unauthorized();
        } else if(identity.authVersion()!=0) throw JwtTokenService.unauthorized();
    }
}
