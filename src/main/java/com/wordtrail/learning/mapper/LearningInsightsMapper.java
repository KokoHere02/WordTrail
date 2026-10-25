package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.WordReviewLog;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface LearningInsightsMapper extends BaseMapper<WordReviewLog> {
    record RecordedDay(LocalDate learningDate, long answerCount, long studiedCount, long trainingCount) {}

    // A correction never creates another active day. Dates come from Shanghai event dates,
    // not a practice batch's creation/completion time or the user's current memory state.
    @Select("""
        SELECT learning_date,SUM(CASE WHEN action='ANSWER' THEN 1 ELSE 0 END) AS answer_count,
          COUNT(DISTINCT word_id) AS studied_count,COUNT(*) AS training_count
        FROM (
        """ + LearningActivitySql.EVENTS + """
        ) activity WHERE task_type=#{source} GROUP BY learning_date ORDER BY learning_date ASC
        """)
    List<RecordedDay> recordedDays(@Param("userId") long userId, @Param("source") String source,
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
