package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.WordReviewLog;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface WordReviewLogMapper extends BaseMapper<WordReviewLog> {
    record HistoryEvent(Long logId, Long batchId, Long itemId, LocalDate learningDate,
        LocalDateTime answeredAt, int result, String taskType, String eventType, Long correctsLogId,
        int attemptNo, boolean affectsSchedule, String scheduleBefore, String scheduleAfter) {}

    String HISTORY_FROM = """
        FROM word_review_log l INNER JOIN learning_batch b ON b.id = l.batch_id AND b.user_id = l.user_id
        WHERE l.user_id = #{userId} AND l.word_id = #{wordId} AND b.practice_mode='RECOGNITION'
        """;

    @Select("SELECT COUNT(*) " + HISTORY_FROM)
    long countHistory(@Param("userId") long userId, @Param("wordId") long wordId);

    @Select("""
        SELECT l.id AS log_id, l.batch_id, l.batch_item_id AS item_id, l.learning_date, l.answered_at,
               l.result, b.task_type, l.event_type, l.corrects_log_id, l.attempt_no, l.affects_schedule,
               l.schedule_before, l.schedule_after
        """ + HISTORY_FROM + " ORDER BY l.answered_at DESC, l.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<HistoryEvent> history(@Param("userId") long userId, @Param("wordId") long wordId,
            @Param("offset") long offset, @Param("limit") int limit);
}
