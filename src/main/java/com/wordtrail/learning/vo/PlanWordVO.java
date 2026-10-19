package com.wordtrail.learning.vo;

import java.time.LocalDateTime;

public record PlanWordVO(Long wordId, String word, String phonetic, String translation,
        String planStatus, LocalDateTime joinedAt, LocalDateTime firstLearnedAt,
        LocalDateTime lastLearnedAt, Integer lastResult, LocalDateTime nextReviewAt, int reviewLevel) {}
