package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record DictationAnswerRequest(@NotNull Action action, @Size(max=255) String input,
        @NotNull @Min(1) @Max(1) Integer attemptNo,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{36}") String presentationId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String requestId) {
    public enum Action { ANSWER, REVEAL }
    @JsonAnySetter public void reject(String key,Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
