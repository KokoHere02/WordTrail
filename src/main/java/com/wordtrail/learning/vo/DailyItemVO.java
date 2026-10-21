package com.wordtrail.learning.vo;
import com.fasterxml.jackson.annotation.JsonInclude;

public record DailyItemVO(Long itemId, Long wordId, String word, String phonetic,
        @JsonInclude(JsonInclude.Include.NON_NULL) String translation,
        String itemType, String learningStatus, Integer result, int answerCount,
        Integer nextAttemptNo, Long nextItemId, Long correctableLogId,
        Integer reinforcementRemaining, int attemptLimit, String completionReason) {}
