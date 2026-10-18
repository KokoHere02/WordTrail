package com.wordtrail.user.vo;

import com.wordtrail.user.entity.UserAccount;
import java.io.Serializable;

public record UserVO(Long id, String username, String nickname) implements Serializable {
    public static UserVO from(UserAccount account) {
        return new UserVO(account.getId(), account.getUsername(), account.getNickname());
    }
}
