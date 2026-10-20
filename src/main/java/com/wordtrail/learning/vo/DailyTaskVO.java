package com.wordtrail.learning.vo;
import java.time.LocalDate;

public record DailyTaskVO(Long batchId, LocalDate learningDate, int dailyTarget, String taskType, String status,
        long totalCount, long completedCount, long answerCount, long unfamiliarCount,
        long newCount, long reviewCount, Long nextItemId) {}
