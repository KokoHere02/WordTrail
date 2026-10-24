package com.wordtrail.user.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.CodePointLength;

@Getter
@Setter
public class LoginRequest {
    @NotNull(message = "username 不能为空")
    @Pattern(regexp = "[A-Za-z0-9_]{4,32}", message = "username 格式不正确")
    private String username;

    @NotNull(message = "password 不能为空")
    @CodePointLength(min = 8, max = 64, message = "password 必须为 8～64 个字符")
    private String password;
    @CodePointLength(max=100,message="deviceName 最长 100 个字符")
    private String deviceName;
}
