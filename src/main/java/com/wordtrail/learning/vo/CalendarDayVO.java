package com.wordtrail.learning.vo;

import java.time.LocalDate;

public record CalendarDayVO(LocalDate learningDate, long completedCount, long answerCount,
        boolean hasLearningActivity, boolean statisticsComplete, long incompleteWordCount) {}
