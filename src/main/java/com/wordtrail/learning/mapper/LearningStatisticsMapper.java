package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.LearningBatchItem;
import com.wordtrail.learning.vo.DailyStatisticsVO;
import com.wordtrail.learning.vo.StatisticsWordVO;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface LearningStatisticsMapper extends BaseMapper<LearningBatchItem> {
    // Restrict both items and events to this user's DAILY date range before aggregating.
    // The item is the transactionally maintained effective feedback, including corrections.
    // Compare it with the latest answer/correction for completeness; never infer missing attempts.
    String DATE_SCOPE = """
        b.user_id = #{userId} AND b.task_type = 'DAILY' AND b.practice_mode='RECOGNITION'
        AND b.learning_date >= #{from} AND b.learning_date <= #{to}
        """;
    String EVENTS_FROM = """
        FROM learning_batch b
        INNER JOIN learning_batch_item i ON i.batch_id = b.id
        INNER JOIN word_review_log l ON l.batch_item_id = i.id AND l.batch_id = b.id
          AND l.user_id = b.user_id AND l.word_id = i.word_id AND l.learning_date = b.learning_date
        WHERE
        """ + DATE_SCOPE;
    String EVENT_COUNTS = """
        SELECT l.batch_item_id, COUNT(*) AS event_count,
               COUNT(CASE WHEN l.event_type = 'ANSWER' THEN 1 END) AS answer_count,
               MAX(CASE WHEN l.event_type = 'ANSWER' THEN l.id END) AS latest_answer_id
        """ + EVENTS_FROM + " GROUP BY l.batch_item_id ";
    String LATEST_CORRECTIONS = """
        SELECT l.batch_item_id, l.corrects_log_id, MAX(l.id) AS correction_id
        """ + EVENTS_FROM + """
        AND l.event_type = 'CORRECTION'
        GROUP BY l.batch_item_id, l.corrects_log_id
        """;
    String FACTS = """
        SELECT i.id AS item_id, i.word_id, b.learning_date, i.item_type, i.learning_status,i.completion_reason,
               COALESCE(activity.answer_count, 0) AS answer_count,
               COALESCE(activity.training_count, 0) AS training_count,
               COALESCE(i.result, c.result, original.result) AS last_result,
               CASE WHEN COALESCE(activity.training_count, 0)>0 OR COALESCE(a.event_count, 0) > 0 OR i.answer_count > 0
                         OR i.result IS NOT NULL OR i.answered_at IS NOT NULL
                         OR i.learning_status IN ('DONE', 'RELEARNING') THEN 1 ELSE 0 END AS studied,
               CASE WHEN i.answer_count = COALESCE(a.answer_count, 0)
                     AND i.study_attempt_count=COALESCE(activity.unified_count,0)
                     AND (a.latest_answer_id IS NOT NULL OR
                          (COALESCE(a.event_count, 0) = 0 AND i.result IS NULL
                           AND i.answered_at IS NULL AND i.learning_status IN ('PENDING', 'SKIPPED')))
                     AND (a.latest_answer_id IS NULL OR i.result = COALESCE(c.result, original.result))
                    THEN 1 ELSE 0 END AS statistics_complete
        FROM learning_batch b
        INNER JOIN learning_batch_item i ON i.batch_id = b.id
        LEFT JOIN (
        """ + EVENT_COUNTS + """
        ) a ON a.batch_item_id = i.id
        LEFT JOIN word_review_log original ON original.id = a.latest_answer_id
        LEFT JOIN (
        """ + LATEST_CORRECTIONS + """
        ) latest ON latest.batch_item_id = i.id AND latest.corrects_log_id = a.latest_answer_id
        LEFT JOIN word_review_log c ON c.id = latest.correction_id
        LEFT JOIN (
          SELECT item_id,COUNT(*) AS training_count,SUM(unified) AS unified_count,
            SUM(CASE WHEN action='ANSWER' THEN 1 ELSE 0 END) AS answer_count
          FROM (
        """ + LearningActivitySql.EVENTS + """
          ) events WHERE task_type='DAILY' GROUP BY item_id
        ) activity ON activity.item_id=i.id
        WHERE
        """ + DATE_SCOPE;

    @Select("""
        SELECT learning_date,
               COUNT(DISTINCT CASE WHEN learning_status = 'DONE' THEN word_id END) AS completed_count,
               SUM(answer_count) AS answer_count,
               COUNT(DISTINCT CASE WHEN learning_status = 'DONE' AND item_type = 'NEW' THEN word_id END) AS new_completed_count,
               COUNT(DISTINCT CASE WHEN learning_status = 'DONE' AND item_type = 'REVIEW' THEN word_id END) AS review_completed_count,
               COUNT(DISTINCT CASE WHEN studied = 1 AND last_result IN (2, 3) THEN word_id END) AS unfamiliar_count,
               CASE WHEN MAX(studied) = 1 THEN TRUE ELSE FALSE END AS has_learning_activity,
               CASE WHEN MIN(statistics_complete) = 1 THEN TRUE ELSE FALSE END AS statistics_complete,
               COUNT(DISTINCT CASE WHEN statistics_complete = 0 THEN word_id END) AS incomplete_word_count,
               SUM(training_count) AS training_count,
               COUNT(DISTINCT CASE WHEN training_count>0 THEN word_id END) AS studied_count,
               COUNT(DISTINCT CASE WHEN learning_status='DONE' AND completion_reason='RECALLED'
                  AND last_result=1 AND statistics_complete=1 THEN word_id END) AS recalled_count
        FROM (
        """ + FACTS + """
        ) item_statistics GROUP BY learning_date ORDER BY learning_date
        """)
    List<DailyStatisticsVO> dailyStatistics(@Param("userId") long userId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("SELECT COUNT(*) FROM (" + FACTS + ") item_statistics WHERE studied = 1")
    long countStudiedWords(@Param("userId") long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Select("""
        SELECT s.word_id, w.word, w.translation, s.item_type, s.last_result, s.answer_count,
               CASE WHEN s.learning_status = 'DONE' THEN TRUE ELSE FALSE END AS completed,
               CASE WHEN s.statistics_complete = 1 THEN TRUE ELSE FALSE END AS statistics_complete
        FROM (
        """ + FACTS + """
        ) s INNER JOIN word w ON w.id = s.word_id
        WHERE s.studied = 1 ORDER BY s.item_id ASC
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<StatisticsWordVO> studiedWords(@Param("userId") long userId,
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("offset") long offset, @Param("limit") int limit);
}
