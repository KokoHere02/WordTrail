package com.wordtrail.word.vo;

import java.time.LocalDateTime;
import java.util.List;

public record WordDetailVO(Long id, String word, String phonetic, String translation,
        List<PersonalBook> personalBooks, Plan plan) {
    public record PersonalBook(Long id, String name) {}
    public record Plan(String planStatus, LocalDateTime joinedAt, LocalDateTime firstLearnedAt,
            LocalDateTime lastLearnedAt, Integer lastResult, LocalDateTime nextReviewAt,
            int reviewLevel, boolean started, boolean due) {}
}
