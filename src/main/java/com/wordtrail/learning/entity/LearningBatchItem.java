package com.wordtrail.learning.entity;

import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Table("learning_batch_item")
public class LearningBatchItem {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long batchId;
    private Long wordId;
    private Integer sortOrder;
    private Integer result;
    private LocalDateTime answeredAt;
    private String itemType;
    private String learningStatus;
    private Long queueOrder;
    private Integer answerCount;
    private Integer relearnCount;
    private LocalDateTime completedAt;
    private String statusBeforeSkip;
    private LocalDateTime skippedAt;
    private Integer reinforcementRemaining;
    private String completionReason;
    private String dictationTarget;
    private String dictationTranslation;
    private Boolean dictationHadFailure;
    private Integer dictationCorrectStreak;
    private String dictationPresentationId;
    private String studyWord;
    private String studyTranslation;
    private String studyPhonetic;
    private Boolean studyHintUsed = false;
    private Integer studyAttemptCount = 0;
    private String studyPresentationId;
    private Long studyPresentationVersion;
    private Boolean studyExtraTraining = false;
}
