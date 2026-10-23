package com.wordtrail.learning.vo;

import java.time.LocalDateTime;
import com.wordtrail.word.vo.TranslatedWord;

public record ResultWordVO(Long itemId, Long wordId, String word, String phonetic,
                           String translation, Integer result, LocalDateTime answeredAt,
                           long answerCount, boolean statisticsComplete) implements TranslatedWord {}
