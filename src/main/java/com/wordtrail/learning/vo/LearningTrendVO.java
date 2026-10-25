package com.wordtrail.learning.vo;

import java.time.LocalDate;
import java.util.List;

public record LearningTrendVO(String source, int days, LocalDate from, LocalDate to,
        long totalCompletedCount, long totalAnswerCount, StatisticsCoverageVO coverage,
        List<LearningTrendDayVO> records, long totalTrainingCount, long totalRecalledCount) {}
