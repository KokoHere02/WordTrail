package com.wordtrail.book.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;

public record BookImportRequest(@NotBlank String text, BookImportFormat format) {
    @JsonAnySetter public void reject(String field,Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
