package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.WordReviewLog;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface LearningInsightsMapper extends BaseMapper<WordReviewLog> {
    record RecordedDay(LocalDate learningDate, long answerCount, long studiedCount) {}

    // A correction never creates another active day. Dates come from Shanghai event dates,
    // not a practice batch's creation/completion time or the user's current memory state.
    @Select("""
        <script>
        SELECT l.learning_date,COUNT(*) AS answer_count,COUNT(DISTINCT l.word_id) AS studied_count
        FROM word_review_log l JOIN learning_batch b ON b.id=l.batch_id AND b.user_id=l.user_id
        JOIN learning_batch_item i ON i.id=l.batch_item_id AND i.batch_id=b.id AND i.word_id=l.word_id
        WHERE l.user_id=#{userId} AND b.task_type=#{source} AND l.event_type='ANSWER'
          AND l.learning_date &lt;= #{to}
          AND (b.task_type='PRACTICE' OR b.learning_date=l.learning_date)
        <if test="from != null">AND l.learning_date &gt;= #{from}</if>
        GROUP BY l.learning_date ORDER BY l.learning_date ASC
        </script>
        """)
    List<RecordedDay> recordedDays(@Param("userId") long userId, @Param("source") String source,
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
