package com.wordtrail.user.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.CodePointLength;

@Getter
@Setter
public class RegisterRequest {
    @NotNull(message = "username 不能为空")
    @Pattern(regexp = "[A-Za-z0-9_]{4,32}", message = "username 必须为 4～32 位英文字母、数字或下划线")
    private String username;

    @NotNull(message = "password 不能为空")
    @CodePointLength(min = 8, max = 64, message = "password 必须为 8～64 个字符")
    private String password;

    @CodePointLength(max = 64, message = "nickname 最长 64 个字符")
    private String nickname;
}
