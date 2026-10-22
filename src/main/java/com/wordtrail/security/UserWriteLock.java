package com.wordtrail.security;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.mybatisflex.core.query.QueryMethods.column;

/** Call inside a transaction before locking books or learning batches. */
@Component
@RequiredArgsConstructor
public class UserWriteLock {
    private final UserMapper users;

    public void lock(long userId) {
        if (users.selectOneByQuery(QueryWrapper.create().select(column("id"))
                .where(column("id").eq(userId)).forUpdate()) == null) { throw JwtTokenService.unauthorized(); }
    }
}
