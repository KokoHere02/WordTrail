package com.wordtrail.learning.entity;

import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Table("learning_batch")
public class LearningBatch {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long userId;
    private Long bookId;
    private String selectionType;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
