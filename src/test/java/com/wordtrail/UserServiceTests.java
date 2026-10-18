package com.wordtrail;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.security.*;
import com.wordtrail.user.dto.RegisterRequest;
import com.wordtrail.user.entity.UserAccount;
import com.wordtrail.user.mapper.*;
import com.wordtrail.user.service.UserService;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTests {
    @Test void concurrentUniqueKeyConflictBecomesSafeDuplicateResponse() {
        var users = mock(UserMapper.class);
        var passwords = mock(PasswordService.class);
        when(passwords.hash("password123")).thenReturn("private-hash");
        doThrow(new DuplicateKeyException("sensitive database details"))
            .when(users).insertSelective(any(UserAccount.class));
        var request = new RegisterRequest();
        request.setUsername("RACE_USER");
        request.setPassword("password123");
        var service = new UserService(users, mock(RefreshTokenMapper.class), passwords,
            mock(JwtTokenService.class), Clock.systemUTC(), Duration.ofDays(7));
        var error = assertThrows(BusinessException.class, () -> service.register(request));
        assertEquals(409, error.status().value());
        assertEquals("USERNAME_EXISTS", error.code());
        assertEquals("用户名已存在", error.getMessage());
        assertNull(error.getCause());
        verify(users).insertSelective(argThat(account ->
            account.getUsername().equals("race_user") && account.getPasswordHash().equals("private-hash")));
    }
}
