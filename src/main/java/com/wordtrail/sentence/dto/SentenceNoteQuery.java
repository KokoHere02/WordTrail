package com.wordtrail.sentence.dto;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class SentenceNoteQuery extends SentenceQuestionQuery {
    private SentenceNoteRequest.ErrorTag errorTag;
}
