package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CreateDictationRequest {
    public enum Source { PLAN, MISTAKES }
    @NotNull private Source source=Source.PLAN;
    @Positive private Long bookId;
    @Min(1) @Max(100) private Integer count;
    private List<@NotNull @Positive Long> wordIds;
    @JsonAnySetter public void reject(String key,Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
