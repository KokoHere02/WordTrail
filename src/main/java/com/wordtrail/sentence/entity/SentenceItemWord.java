package com.wordtrail.sentence.entity;
import com.mybatisflex.annotation.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Table("sentence_item_word")
public class SentenceItemWord {
    @Id private Long itemId;
    @Id private Long wordId;
    private String word;
}
