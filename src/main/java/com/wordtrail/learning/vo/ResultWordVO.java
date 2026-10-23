package com.wordtrail.learning.vo;

import java.time.LocalDateTime;

public record ResultWordVO(Long itemId, Long wordId, String word, String phonetic,
                           String translation, Integer result, LocalDateTime answeredAt,
                           long answerCount, boolean statisticsComplete) {}
