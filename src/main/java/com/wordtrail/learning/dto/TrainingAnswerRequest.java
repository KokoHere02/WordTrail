package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record TrainingAnswerRequest(
        @NotNull @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String requestId,
        @NotNull @Pattern(regexp="[a-f0-9-]{36}") String presentationId,
        @NotNull @PositiveOrZero Long modeVersion,
        @NotNull @Positive Integer attemptNo,
        @NotNull Action action,
        @Size(max=255) String input,
        @Min(1) @Max(3) Integer result) {
    public enum Action { ANSWER, REVEAL }
    @JsonAnySetter public void reject(String key, Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
