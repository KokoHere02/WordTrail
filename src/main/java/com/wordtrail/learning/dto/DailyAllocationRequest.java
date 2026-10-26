package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record DailyAllocationRequest(@NotNull @Min(0) @Max(200) Integer newWordTarget,
        @NotNull @Min(0) @Max(200) Integer reviewLimit) {
    @AssertTrue(message="新词与复习配额之和必须在 1～200 之间")
    public boolean isValidTotal() { return newWordTarget==null || reviewLimit==null || newWordTarget+reviewLimit>=1 && newWordTarget+reviewLimit<=200; }
    @JsonAnySetter public void reject(String key,Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
