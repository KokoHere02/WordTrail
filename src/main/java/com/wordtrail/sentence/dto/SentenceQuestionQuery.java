package com.wordtrail.sentence.dto;

import com.wordtrail.learning.dto.WordHistoryQuery;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SentenceQuestionQuery extends WordHistoryQuery {
    @Min(1) @Max(3) private Integer difficulty;
    @Positive private Long wordId;
}
