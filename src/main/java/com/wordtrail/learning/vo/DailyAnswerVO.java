package com.wordtrail.learning.vo;
import java.time.LocalDateTime;
import com.wordtrail.word.vo.TranslatedWord;
public record DailyAnswerVO(Long logId, Long itemId, int result, String translation,
                            LocalDateTime answeredAt, boolean correction, DailyTaskVO progress) implements TranslatedWord {}
