package com.wordtrail.sentence.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CreateSentenceSessionRequest {
    public enum Source { BANK, REVIEW, FAVORITES, DUE }
    @NotNull private Source source=Source.BANK;
    @Min(1) @Max(50) private Integer count;
    @Min(1) @Max(3) private Integer difficulty;
    @Positive private Long wordId;
    @Size(max=50) private List<@NotNull @Positive Long> sentenceIds;
    @JsonAnySetter public void reject(String key,Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
