package com.wordtrail.learning.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wordtrail.learning.dto.StudyMode;
import com.wordtrail.word.vo.TranslatedWord;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrainingQuestionVO(Long itemId, StudyMode mode, long modeVersion, String presentationId,
        int attemptNo, Long wordId, String word, String phonetic, String translation,
        boolean hintUsed, boolean extraTraining, boolean answerRevealed, String phase,
        String itemStatus, String completionReason, DailyTaskVO progress,String audioUrl,PronunciationVO pronunciation) implements TranslatedWord {}
