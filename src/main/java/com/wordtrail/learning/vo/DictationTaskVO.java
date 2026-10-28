package com.wordtrail.learning.vo;

import java.time.LocalDateTime;

public record DictationTaskVO(Long batchId,Long bookId,String taskType,String practiceMode,String status,
        long totalCount,long completedCount,Long passedCount,Long needsPracticeCount,long attemptCount,
        Long nextItemId,int attemptLimit,LocalDateTime createdAt,LocalDateTime completedAt,
        boolean singleAttempt,boolean canContinue,Double accuracy) {}
