package com.wordtrail.user.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

// No toString: this response intentionally contains raw tokens and must never be logged.
@Getter
@AllArgsConstructor
public class TokenVO {
    private final String accessToken;
    private final String refreshToken;
    private final String tokenType;
    private final long expiresIn;
    private final UserVO user;
}
