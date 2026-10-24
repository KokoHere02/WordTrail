package com.wordtrail.sentence.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SentenceManagementQuery extends SentenceQuestionQuery {
    private SentenceQuestionWriteRequest.Status status;
    @Size(max=100) private String keyword;
}
