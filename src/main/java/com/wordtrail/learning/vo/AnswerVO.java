package com.wordtrail.learning.vo;

import java.time.LocalDateTime;
import com.wordtrail.word.vo.TranslatedWord;

public record AnswerVO(Long itemId, Integer result, String translation, LocalDateTime answeredAt,
                       String status, long wordCount, long answeredCount, Long nextItemId) implements TranslatedWord {}
