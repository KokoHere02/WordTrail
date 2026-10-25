package com.wordtrail.learning.entity;

import com.mybatisflex.annotation.*;
import java.time.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Table("word_review_log")
public class WordReviewLog {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long userId;
    private Long wordId;
    private Long batchId;
    private Long batchItemId;
    private String requestId;
    private String eventType;
    private Long correctsLogId;
    private LocalDate learningDate;
    private Integer result;
    private Integer attemptNo;
    private Boolean affectsSchedule;
    private String scheduleBefore;
    private String scheduleAfter;
    private LocalDateTime answeredAt;
    private LocalDateTime createdAt;
    private String feedbackMode;
}
