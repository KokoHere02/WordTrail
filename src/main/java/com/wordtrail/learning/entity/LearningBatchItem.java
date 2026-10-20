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
}
