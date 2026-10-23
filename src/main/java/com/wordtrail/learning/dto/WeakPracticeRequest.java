package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;

public record WeakPracticeRequest(@NotNull LearningSource source,
        @NotEmpty List<@NotNull @Positive Long> wordIds) {
    @JsonAnySetter public void reject(String field, Object value) {
        throw new IllegalArgumentException("不支持的请求字段");
    }
}
