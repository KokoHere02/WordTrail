package com.wordtrail.learning.vo;

import com.wordtrail.word.vo.TranslatedWord;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

public record DictationAnswerVO(Long logId,Long itemId,String action,String submittedAnswer,
        @JsonInclude(JsonInclude.Include.NON_NULL) String expectedWord,
        @JsonInclude(JsonInclude.Include.NON_NULL) String translation,
        @JsonInclude(JsonInclude.Include.NON_NULL) String verdict,int attemptNo,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer correctStreak,String itemStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) String completionReason,
        LocalDateTime answeredAt,boolean affectsSchedule,DictationTaskVO progress) implements TranslatedWord {}
