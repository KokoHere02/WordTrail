package com.wordtrail.learning.vo;

import com.wordtrail.word.vo.TranslatedWord;
import java.time.LocalDateTime;

public record TrainingAnswerVO(Long trainingId, Long itemId, Long wordId, String mode, String action, String verdict,
        String input, Integer selfAssessment, String word, String translation, boolean hintUsed,
        boolean extraTraining, int attemptNo, Long reviewLogId, boolean affectsSchedule,
        LocalDateTime answeredAt, String itemStatus, String completionReason, DailyTaskVO progress) implements TranslatedWord {}
