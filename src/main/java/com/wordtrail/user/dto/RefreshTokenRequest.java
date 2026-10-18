package com.wordtrail.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefreshTokenRequest {
    @NotBlank(message = "refreshToken 不能为空")
    @Size(max = 512, message = "refreshToken 格式不正确")
    private String refreshToken;
}
