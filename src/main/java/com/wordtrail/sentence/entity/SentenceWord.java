package com.wordtrail.sentence.entity;
import com.mybatisflex.annotation.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Table("sentence_word")
public class SentenceWord {
    @Id private Long sentenceId;
    @Id private Long wordId;
}
