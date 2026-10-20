package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

public record LearningSettingsRequest(
        @NotNull(message = "dailyTarget 不能为空")
        @Min(value = 1, message = "dailyTarget 必须在 1～200 之间")
        @Max(value = 200, message = "dailyTarget 必须在 1～200 之间") Integer dailyTarget) {
    // Keep strict JSON integer input local to this endpoint: do not coerce "20", 20.0 or 2e1.
    @JsonCreator
    public static LearningSettingsRequest fromJson(@JsonProperty("dailyTarget") JsonNode value) {
        if (value == null || value.isNull()) { return new LearningSettingsRequest(null); }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("dailyTarget 必须为整数");
        }
        return new LearningSettingsRequest(value.asInt());
    }

    @JsonAnySetter
    public void reject(String field, Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
