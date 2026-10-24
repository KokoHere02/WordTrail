package com.wordtrail.sentence.entity;
import com.mybatisflex.annotation.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Table("user_sentence_note_tag")
public class SentenceNoteTag {
    @Id private Long noteId;
    @Id private String errorTag;
}
