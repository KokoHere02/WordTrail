package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record DailyAnswerRequest(@NotNull @Min(1) @Max(3) Integer result,
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String requestId,
        @NotNull @Min(1) @Max(3) Integer attemptNo) {
    @JsonAnySetter public void reject(String key, Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
