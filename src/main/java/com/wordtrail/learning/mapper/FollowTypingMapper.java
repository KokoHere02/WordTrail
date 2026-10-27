package com.wordtrail.learning.mapper;

import com.wordtrail.learning.vo.FollowTypingStatisticsVO;
import java.time.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FollowTypingMapper {
    record Candidate(long wordId,String word,String phonetic,String translation,Integer recognitionResult,
            String recallVerdict,Boolean recallHintUsed,LocalDateTime lastPracticedAt,int recentSelectionCount) {}

    @Select("""
        WITH recognition AS (
          SELECT l.word_id,COALESCE((SELECT c.result FROM word_review_log c
            WHERE c.user_id=l.user_id AND c.corrects_log_id=l.id AND c.event_type='CORRECTION'
            ORDER BY c.id DESC LIMIT 1),l.result) AS result,
            ROW_NUMBER() OVER(PARTITION BY l.word_id ORDER BY l.answered_at DESC,l.id DESC) AS rn
          FROM word_review_log l WHERE l.user_id=#{userId} AND l.event_type='ANSWER' AND l.feedback_mode='RECOGNITION'
        ), recall_events AS (
          SELECT word_id,verdict,hint_used,answered_at,id,1 AS source_rank FROM word_training_attempt
            WHERE user_id=#{userId} AND mode IN ('DICTATION','LISTEN_DICTATION')
          UNION ALL
          SELECT word_id,verdict,had_failure_after,answered_at,id,0 FROM dictation_review_log WHERE user_id=#{userId}
        ), recall_ranked AS (
          SELECT e.*,ROW_NUMBER() OVER(PARTITION BY word_id ORDER BY answered_at DESC,source_rank DESC,id DESC) AS rn
          FROM recall_events e
        ), practiced AS (
          SELECT word_id,MAX(answered_at) AS last_practiced_at FROM word_training_attempt
          WHERE user_id=#{userId} AND mode='FOLLOW_TYPING' GROUP BY word_id
        ), selections AS (
          SELECT i.word_id,COUNT(*) AS recent_selection_count FROM learning_batch b
          JOIN learning_batch_item i ON i.batch_id=b.id WHERE b.user_id=#{userId}
            AND b.practice_mode='FOLLOW_TYPING' AND b.created_at>#{since} GROUP BY i.word_id
        )
        SELECT s.word_id,w.word,w.phonetic,w.translation,r.result AS recognition_result,
          d.verdict AS recall_verdict,d.hint_used AS recall_hint_used,p.last_practiced_at,
          COALESCE(x.recent_selection_count,0) AS recent_selection_count
        FROM user_word_state s JOIN word w ON w.id=s.word_id
        LEFT JOIN recognition r ON r.word_id=s.word_id AND r.rn=1
        LEFT JOIN recall_ranked d ON d.word_id=s.word_id AND d.rn=1
        LEFT JOIN practiced p ON p.word_id=s.word_id LEFT JOIN selections x ON x.word_id=s.word_id
        WHERE s.user_id=#{userId} AND s.plan_status='ACTIVE'
          AND COALESCE(x.recent_selection_count,0)<2 ORDER BY s.id
        """)
    List<Candidate> candidates(@Param("userId") long userId,@Param("since") LocalDateTime since);

    @Select("""
        SELECT #{date} AS learning_date,COUNT(*) AS answer_count,
          COALESCE(SUM(CASE WHEN t.verdict='CORRECT' THEN 1 ELSE 0 END),0) AS correct_count,
          CASE WHEN COUNT(*)=0 THEN NULL ELSE 1.0*SUM(CASE WHEN t.verdict='CORRECT' THEN 1 ELSE 0 END)/COUNT(*) END AS input_accuracy,
          COUNT(DISTINCT t.word_id) AS practiced_word_count,
          COUNT(DISTINCT CASE WHEN t.verdict='CORRECT' THEN t.word_id END) AS completed_word_count,
          MAX(t.answered_at) AS last_practiced_at
        FROM word_training_attempt t JOIN learning_batch b ON b.id=t.batch_id AND b.user_id=t.user_id
        WHERE t.user_id=#{userId} AND b.practice_mode='FOLLOW_TYPING' AND t.learning_date=#{date} AND t.action='ANSWER'
        """)
    FollowTypingStatisticsVO statistics(@Param("userId") long userId,@Param("date") LocalDate date);
}
