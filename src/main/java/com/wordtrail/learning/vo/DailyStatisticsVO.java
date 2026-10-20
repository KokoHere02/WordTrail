package com.wordtrail.learning.vo;

import java.time.LocalDate;

public record DailyStatisticsVO(LocalDate learningDate, long completedCount, long answerCount,
        long newCompletedCount, long reviewCompletedCount, long unfamiliarCount,
        boolean hasLearningActivity, boolean statisticsComplete, long incompleteWordCount) {}
