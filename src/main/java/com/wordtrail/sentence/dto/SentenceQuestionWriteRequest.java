package com.wordtrail.sentence.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SentenceQuestionWriteRequest {
    public enum Status { ACTIVE, INACTIVE }
    @NotBlank @Size(max=2000) private String englishText;
    @NotBlank @Size(max=2000) private String referenceTranslation;
    @NotBlank @Size(max=4000) private String explanation;
    @NotNull @Min(1) @Max(3) private Integer difficulty=1;
    @NotBlank @Size(max=255) private String source;
    @Size(max=512) private String sourceReference;
    @NotNull private Status status=Status.ACTIVE;
    @NotNull @PositiveOrZero private Long version=0L;
    @NotNull @Size(max=50) private List<@NotNull @Positive Long> wordIds=new ArrayList<>();
    @JsonAnySetter public void reject(String key,Object value) {throw new IllegalArgumentException("不支持的请求字段");}
}
