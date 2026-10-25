package com.wordtrail.learning.vo;

import java.time.LocalDate;
import java.util.List;

public record TrainingStatisticsVO(LocalDate learningDate, long completedWordCount, List<ModeStatistics> modes) {
    public record ModeStatistics(String mode, long trainingCount, long answerCount, long revealCount,
            long correctCount, Double inputAccuracy, long hintedAnswerCount,
            long recognizedCount, long uncertainCount, long unknownCount) {}
}
