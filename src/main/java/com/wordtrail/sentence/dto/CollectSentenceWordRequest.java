package com.wordtrail.sentence.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
public record CollectSentenceWordRequest(@NotNull @Positive Long bookId,Boolean joinPlan) {
    @JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("不支持的请求字段");}
}
