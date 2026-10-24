package com.wordtrail.sentence.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;
public record WordSentencePracticeRequest(@NotEmpty @Size(max=100) List<@NotNull @Positive Long> wordIds,
        @Min(1) @Max(50) Integer count,@Min(1) @Max(3) Integer difficulty) {
    @JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("不支持的请求字段");}
}
