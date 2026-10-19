package com.wordtrail.learning.vo;
import java.time.LocalDateTime;
public record DailyAnswerVO(Long logId, Long itemId, int result, String translation,
                            LocalDateTime answeredAt, boolean correction, DailyTaskVO progress) {}
