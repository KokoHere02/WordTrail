package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CreateFollowTypingRequest {
    @NotNull @Pattern(regexp="[A-Za-z0-9_-]{8,64}") private String requestId;
    @NotNull @Min(10) @Max(20) private Integer count=20;
    @JsonAnySetter public void reject(String key,Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
