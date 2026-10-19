package com.wordtrail.learning.vo;

import java.time.LocalDateTime;

public record AnswerVO(Long itemId, Integer result, String translation, LocalDateTime answeredAt,
                       String status, long wordCount, long answeredCount, Long nextItemId) {}
