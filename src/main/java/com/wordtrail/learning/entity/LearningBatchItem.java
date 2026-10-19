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
}
