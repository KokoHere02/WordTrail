package com.wordtrail.learning.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;

public record WordHistoryVO(Long logId, Long batchId, Long itemId, LocalDate learningDate,
        LocalDateTime answeredAt, int result, String taskType, String eventType, Long correctsLogId,
        int attemptNo, boolean affectsSchedule, JsonNode scheduleBefore, JsonNode scheduleAfter,
        boolean scheduleAvailable) {}
