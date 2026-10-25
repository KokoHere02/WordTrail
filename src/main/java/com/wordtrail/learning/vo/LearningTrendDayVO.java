package com.wordtrail.learning.vo;

import java.time.LocalDate;

public record LearningTrendDayVO(LocalDate learningDate, long completedCount, long answerCount,
        long studiedCount, boolean hasRecordedAnswers, long trainingCount, long recalledCount, boolean hasLearningActivity) {}
