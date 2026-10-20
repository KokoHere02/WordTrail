package com.wordtrail.learning.vo;

public record StatisticsWordVO(Long wordId, String word, String translation, String itemType,
        Integer lastResult, long answerCount, boolean completed, boolean statisticsComplete) {}
