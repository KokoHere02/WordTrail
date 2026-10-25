package com.wordtrail.learning.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wordtrail.word.vo.TranslatedWord;
import java.time.LocalDateTime;

/** A task inventory is not a presentation and never reveals an unfinished dictation target. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DailyTrainingItemVO(Long itemId, Long wordId, String word, String phonetic, String translation,
        int sortOrder, String itemType, String itemStatus, String completionReason,
        int answerCount, int trainingCount, LocalDateTime completedAt,
        boolean current, boolean canExtraTrain) implements TranslatedWord {}
