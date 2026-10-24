package com.wordtrail.sentence.entity;

import com.mybatisflex.annotation.*;
import java.time.*;
import lombok.Getter;
import lombok.Setter;

/** One immutable submission per item; its one-time self review never replaces the original input. */
@Getter @Setter @Table("sentence_translation_attempt")
public class SentenceTranslationAttempt {
    @Id(keyType=KeyType.Auto) private Long id;
    private Long userId;
    private Long sessionId;
    private Long itemId;
    private Long sentenceId;
    private String submitRequestId;
    private String action;
    private String translation;
    private LocalDate learningDate;
    private LocalDateTime submittedAt;
    private String selfAssessment;
    private String reviewRequestId;
    private LocalDate reviewDate;
    private LocalDateTime reviewedAt;
}
