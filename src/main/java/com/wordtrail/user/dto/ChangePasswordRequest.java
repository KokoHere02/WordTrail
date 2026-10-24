package com.wordtrail.user.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;
public record ChangePasswordRequest(
        @NotNull @CodePointLength(min=8,max=64) String currentPassword,
        @NotNull @CodePointLength(min=8,max=64) String newPassword) {
    @JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("不支持的请求字段");}
    @Override public String toString(){return "ChangePasswordRequest[redacted]";}
}
