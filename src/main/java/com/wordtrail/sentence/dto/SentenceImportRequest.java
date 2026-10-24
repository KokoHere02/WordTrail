package com.wordtrail.sentence.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record SentenceImportRequest(@NotBlank String text,
        @Pattern(regexp="[A-Za-z0-9_-]{8,64}") String requestId) {
    @JsonAnySetter public void reject(String key,Object value) {throw new IllegalArgumentException("不支持的请求字段");}
}
