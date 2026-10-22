package com.wordtrail.word.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WordSearchQuery {
    public enum MatchType { PREFIX, EXACT }
    @NotNull @Min(1) private Integer page = 1;
    @NotNull @Min(1) @Max(100) private Integer size = 20;
    @NotBlank @Size(max = 255) private String keyword;
    @Positive private Long bookId;
    @NotNull private MatchType matchType = MatchType.PREFIX;
}
