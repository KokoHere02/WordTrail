package com.wordtrail.learning.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.wordtrail.word.vo.TranslatedWord;

public record WeakWordVO(Long wordId, String word, String phonetic, String translation,
        String source, int lastResult, Long lastAnswerLogId, LocalDate learningDate,
        LocalDateTime lastAnsweredAt, long answerCount, boolean statisticsComplete) implements TranslatedWord {}
