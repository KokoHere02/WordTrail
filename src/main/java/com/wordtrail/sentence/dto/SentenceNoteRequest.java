package com.wordtrail.sentence.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class SentenceNoteRequest {
    public enum ErrorTag { VOCABULARY, TENSE, NEGATION, STRUCTURE, OTHER }
    @NotNull @Size(max=4000) private String content="";
    @NotNull @Size(max=5) private List<@NotNull ErrorTag> errorTags=new ArrayList<>();
    @Positive private Long attemptId;
    @NotNull @PositiveOrZero private Long version=0L;
    @JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("不支持的请求字段");}
}
