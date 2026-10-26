package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record PronunciationRequest(@NotNull Boolean autoPlay,@NotNull Accent accent,
        @NotNull @DecimalMin("0.50") @DecimalMax("1.50") @Digits(integer=1,fraction=2) BigDecimal playbackRate,
        @NotNull @Min(1) @Max(5) Integer repeatCount) {
    public enum Accent { US, UK }
    @JsonAnySetter public void reject(String key,Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
