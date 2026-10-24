package com.wordtrail.learning.vo;

import com.wordtrail.word.vo.TranslatedWord;
import java.time.LocalDateTime;

public record DictationAnswerVO(Long logId,Long itemId,String action,String submittedAnswer,String expectedWord,
        String translation,String verdict,int attemptNo,int correctStreak,String itemStatus,String completionReason,
        LocalDateTime answeredAt,boolean affectsSchedule,DictationTaskVO progress) implements TranslatedWord {}
