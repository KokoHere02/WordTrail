package com.wordtrail.learning.vo;

import java.time.LocalDate;

public record LearningSummaryVO(LocalDate learningDate, String source, long currentStreak,
        long longestStreak, long totalStudyDays, boolean hasStudiedToday,
        long todayStudiedCount, long todayCompletedCount, long todayAnswerCount,
        DailyGoal goal, StatisticsCoverageVO coverage) {
    public record DailyGoal(int dailyTarget, long completedCount, long remainingCount,
            boolean reached, String targetSource, Long batchId, String taskStatus) {}
}
