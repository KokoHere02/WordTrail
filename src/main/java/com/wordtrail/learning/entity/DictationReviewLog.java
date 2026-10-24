package com.wordtrail.learning.entity;

import com.mybatisflex.annotation.*;
import java.time.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Table("dictation_review_log")
public class DictationReviewLog {
    @Id(keyType=KeyType.Auto) private Long id;
    private Long userId;
    private Long wordId;
    private Long batchId;
    private Long batchItemId;
    private String requestId;
    private String presentationId;
    private String eventType;
    private String inputText;
    private String verdict;
    private Integer attemptNo;
    private LocalDate learningDate;
    private LocalDateTime answeredAt;
    private Integer correctStreakBefore;
    private Integer correctStreakAfter;
    private Boolean hadFailureAfter;
    private String itemStatusAfter;
    private String completionReasonAfter;
    private Boolean affectsSchedule;
}
