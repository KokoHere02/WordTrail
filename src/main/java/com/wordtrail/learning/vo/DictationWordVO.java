package com.wordtrail.learning.vo;

import com.wordtrail.word.vo.TranslatedWord;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record DictationWordVO(Long itemId,Long wordId,String word,String translation,
        String lastVerdict,String lastInput,int attemptCount,int correctStreak,String learningStatus,
        String completionReason,LocalDate learningDate,LocalDateTime answeredAt) implements TranslatedWord {}
