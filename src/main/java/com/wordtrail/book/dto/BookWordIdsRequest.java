package com.wordtrail.book.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;

public record BookWordIdsRequest(@NotEmpty List<@NotNull @Positive Long> wordIds) {
    @JsonAnySetter public void reject(String key, Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
