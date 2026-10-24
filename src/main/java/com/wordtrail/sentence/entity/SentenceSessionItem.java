package com.wordtrail.sentence.entity;

import com.mybatisflex.annotation.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Table("sentence_translation_item")
public class SentenceSessionItem {
    @Id(keyType=KeyType.Auto) private Long id;
    private Long sessionId;
    private Long sentenceId;
    private Integer sortOrder;
    private String status;
    private String englishText;
    private String referenceTranslation;
    private String explanation;
    private Integer difficulty;
    private String source;
    private String sourceReference;
}
