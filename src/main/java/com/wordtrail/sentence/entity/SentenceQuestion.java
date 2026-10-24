package com.wordtrail.sentence.entity;

import com.mybatisflex.annotation.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Table("sentence_question")
public class SentenceQuestion {
    @Id(keyType=KeyType.Auto) private Long id;
    private String englishText;
    private String referenceTranslation;
    private String explanation;
    private Integer difficulty;
    private String source;
    private String sourceReference;
    private String status;
    private Long version;
}
