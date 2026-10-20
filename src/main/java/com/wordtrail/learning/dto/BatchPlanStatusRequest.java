package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;

public record BatchPlanStatusRequest(@NotEmpty List<@NotNull @Positive Long> wordIds,
        @NotNull PlanStatus status) {
    @JsonAnySetter public void reject(String key, Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
