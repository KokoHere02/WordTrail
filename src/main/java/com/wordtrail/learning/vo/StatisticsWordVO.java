package com.wordtrail.learning.vo;

import com.wordtrail.word.vo.TranslatedWord;

public record StatisticsWordVO(Long wordId, String word, String translation, String itemType,
        Integer lastResult, long answerCount, boolean completed, boolean statisticsComplete) implements TranslatedWord {}
