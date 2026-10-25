package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Positive;

// itemId is optional, and may select only an already completed item for extra training.
public record TrainingQuestionRequest(@Positive Long itemId) {
    @JsonAnySetter public void reject(String key, Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
