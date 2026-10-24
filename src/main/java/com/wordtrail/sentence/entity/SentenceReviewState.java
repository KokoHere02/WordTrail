package com.wordtrail.sentence.entity;
import com.mybatisflex.annotation.*;
import java.time.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Table("user_sentence_review_state")
public class SentenceReviewState {
    @Id(keyType=KeyType.Auto) private Long id;
    private Long userId;
    private Long sentenceId;
    private Long lastAttemptId;
    private Integer reviewLevel;
    private String lastAssessment;
    private LocalDateTime reviewedAt;
    private LocalDate nextReviewDate;
}
