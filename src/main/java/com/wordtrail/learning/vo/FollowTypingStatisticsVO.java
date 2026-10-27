package com.wordtrail.learning.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record FollowTypingStatisticsVO(LocalDate learningDate,long answerCount,long correctCount,
        Double inputAccuracy,long practicedWordCount,long completedWordCount,LocalDateTime lastPracticedAt) {}
