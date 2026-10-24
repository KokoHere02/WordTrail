package com.wordtrail.learning.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DictationHistoryVO(Long logId,Long batchId,Long itemId,LocalDate learningDate,LocalDateTime answeredAt,
        String taskType,String practiceMode,String action,String input,String expectedWord,String verdict,int attemptNo,
        int correctStreakBefore,int correctStreakAfter,String itemStatusAfter,String completionReason,boolean affectsSchedule) {}
