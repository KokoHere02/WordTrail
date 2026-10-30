package com.wordtrail.learning.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record LearningRecordVO(String type, Long businessId, LocalDate recordDate, LocalDateTime createdAt,
        String rawStatus, String displayStatus, boolean canContinue,
        long completedCount, long totalCount, Long skippedCount, Long newCount, Long reviewCount,
        Integer dailyTarget, Boolean goalReached, String source, Long bookId, Boolean singleAttempt) {}
