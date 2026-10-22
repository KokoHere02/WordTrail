package com.wordtrail.book.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record BookEditRequest(@NotBlank @Size(max = 128) String name,
        @Size(max = 2000) String description) {
    @JsonAnySetter public void reject(String key, Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
