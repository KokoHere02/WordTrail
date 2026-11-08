package com.wordtrail.user.vo;

import com.wordtrail.user.entity.UserAccount;
import java.io.Serializable;

public record UserVO(Long id, String username, String nickname, String phone, String email) implements Serializable {
    public static UserVO from(UserAccount account) {
        return new UserVO(account.getId(), account.getUsername(), account.getNickname(), account.getPhone(), Boolean.TRUE.equals(account.getEmailVerified()) ? account.getEmail() : null);
    }
}
