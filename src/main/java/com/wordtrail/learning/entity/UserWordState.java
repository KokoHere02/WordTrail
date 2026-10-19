package com.wordtrail.learning.entity;

import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Table("user_word_state")
public class UserWordState {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long userId;
    private Long wordId;
    private Integer lastResult;
    private Long lastBatchItemId;
    private LocalDateTime firstLearnedAt;
    private LocalDateTime lastLearnedAt;
    private String planStatus;
    private LocalDateTime joinedAt;
    private LocalDateTime nextReviewAt;
    private Integer reviewLevel;
}
