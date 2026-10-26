package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.WordTrainingAttempt;
import com.wordtrail.learning.vo.TrainingStatisticsVO.ModeStatistics;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface WordTrainingAttemptMapper extends BaseMapper<WordTrainingAttempt> {
    // Old recognition answers remain visible; linked effective feedback is counted only through its training record.
    @Select("""
        SELECT mode, COUNT(*) AS training_count,
          SUM(CASE WHEN action='ANSWER' THEN 1 ELSE 0 END) AS answer_count,
          SUM(CASE WHEN action='REVEAL' THEN 1 ELSE 0 END) AS reveal_count,
          SUM(CASE WHEN verdict='CORRECT' THEN 1 ELSE 0 END) AS correct_count,
          CASE WHEN mode='RECOGNITION' OR SUM(CASE WHEN action='ANSWER' THEN 1 ELSE 0 END)=0 THEN NULL
            ELSE 1.0*SUM(CASE WHEN verdict='CORRECT' THEN 1 ELSE 0 END)/SUM(CASE WHEN action='ANSWER' THEN 1 ELSE 0 END) END AS input_accuracy,
          SUM(CASE WHEN action='ANSWER' AND hint_used=TRUE THEN 1 ELSE 0 END) AS hinted_answer_count,
          SUM(CASE WHEN self_assessment=1 THEN 1 ELSE 0 END) AS recognized_count,
          SUM(CASE WHEN self_assessment=2 THEN 1 ELSE 0 END) AS uncertain_count,
          SUM(CASE WHEN self_assessment=3 THEN 1 ELSE 0 END) AS unknown_count
        FROM (
          SELECT t.mode,t.action,t.verdict,t.hint_used,
            CASE WHEN t.self_assessment IS NULL THEN NULL ELSE COALESCE(
              (SELECT c.result FROM word_review_log c WHERE c.corrects_log_id=t.review_log_id AND c.user_id=t.user_id
                AND c.event_type='CORRECTION' ORDER BY c.id DESC LIMIT 1),t.self_assessment) END AS self_assessment
          FROM word_training_attempt t JOIN learning_batch b ON b.id=t.batch_id AND b.user_id=t.user_id
          WHERE t.user_id=#{userId} AND t.learning_date=#{date} AND b.task_type='DAILY'
          UNION ALL
          SELECT 'RECOGNITION','ANSWER','SELF_ASSESSED',FALSE,
            COALESCE((SELECT c.result FROM word_review_log c WHERE c.corrects_log_id=l.id AND c.user_id=l.user_id
              AND c.event_type='CORRECTION' ORDER BY c.id DESC LIMIT 1),l.result)
          FROM word_review_log l JOIN learning_batch b ON b.id=l.batch_id AND b.user_id=l.user_id
          WHERE l.user_id=#{userId} AND l.learning_date=#{date} AND b.task_type='DAILY'
            AND l.event_type='ANSWER' AND l.feedback_mode='RECOGNITION'
            AND NOT EXISTS(SELECT 1 FROM word_training_attempt t WHERE t.review_log_id=l.id)
        ) training GROUP BY mode ORDER BY mode
        """)
    List<ModeStatistics> statistics(@Param("userId") long userId,@Param("date") LocalDate date);
}
