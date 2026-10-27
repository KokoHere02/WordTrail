package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.WordTrainingAttempt;
import com.wordtrail.learning.vo.ConsolidationIssueVO;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface ConsolidationMapper extends BaseMapper<WordTrainingAttempt> {
    String ISSUES = """
        WITH facts AS (
          SELECT t.*,COALESCE(i.study_word,w.word) AS expected_word,COALESCE(i.study_translation,w.translation) AS translation,
            i.completion_reason,
            COALESCE((SELECT c.result FROM word_review_log c WHERE c.corrects_log_id=t.review_log_id AND c.user_id=t.user_id
              AND c.event_type='CORRECTION' ORDER BY c.id DESC LIMIT 1),t.self_assessment) AS effective_self
          FROM word_training_attempt t JOIN learning_batch b ON b.id=t.batch_id AND b.user_id=t.user_id
          JOIN learning_batch_item i ON i.id=t.batch_item_id AND i.batch_id=b.id AND i.word_id=t.word_id
          JOIN word w ON w.id=t.word_id
          WHERE t.user_id=#{userId} AND (b.task_type='DAILY' OR b.practice_mode='CONSOLIDATION')
        ), reasons AS (
          SELECT f.*,'SPELLING_ERROR' AS reason,'ERROR' AS category FROM facts f
            WHERE mode IN ('DICTATION','LISTEN_DICTATION') AND action='ANSWER' AND verdict='INCORRECT'
          UNION ALL SELECT f.*,'ANSWER_REVEALED','ERROR' FROM facts f WHERE action='REVEAL' AND mode IN ('DICTATION','LISTEN_DICTATION')
          UNION ALL SELECT f.*,'UNRECOGNIZED','ERROR' FROM facts f WHERE mode='RECOGNITION' AND action='ANSWER' AND effective_self=3
          UNION ALL SELECT f.*,'ATTEMPT_LIMIT','ERROR' FROM facts f WHERE mode!='FOLLOW_TYPING' AND completion_reason='LIMIT_REACHED' AND extra_training=FALSE
            AND action='ANSWER' AND id=(SELECT MAX(x.id) FROM word_training_attempt x
              WHERE x.batch_item_id=f.batch_item_id AND x.user_id=f.user_id AND x.extra_training=FALSE AND x.action='ANSWER')
        ), ranked AS (
          SELECT r.*,ROW_NUMBER() OVER(PARTITION BY word_id,reason ORDER BY id DESC) AS issue_rank,
            COUNT(*) OVER(PARTITION BY word_id,reason) AS occurrence_count FROM reasons r
        ), open_issues AS (
          SELECT r.* FROM ranked r WHERE issue_rank=1 AND NOT EXISTS(
            SELECT 1 FROM facts p WHERE p.word_id=r.word_id AND p.id>r.id AND (
              (r.reason='SPELLING_ERROR' AND p.mode IN ('DICTATION','LISTEN_DICTATION') AND p.verdict='CORRECT' AND p.hint_used=FALSE)
              OR (r.reason='UNRECOGNIZED' AND ((p.mode='RECOGNITION' AND p.effective_self=1) OR (p.mode='DICTATION' AND p.verdict='CORRECT' AND p.hint_used=FALSE)))
              OR (r.reason IN ('ANSWER_REVEALED','ATTEMPT_LIMIT') AND (
                (r.mode='RECOGNITION' AND ((p.mode='RECOGNITION' AND p.effective_self=1) OR (p.mode='DICTATION' AND p.verdict='CORRECT' AND p.hint_used=FALSE)))
                OR (r.mode!='RECOGNITION' AND p.mode IN ('DICTATION','LISTEN_DICTATION') AND p.verdict='CORRECT' AND p.hint_used=FALSE)))
            ))
        )
        """;
    String FILTER = """
        WHERE (#{category}='ALL' OR category=#{category})
          <if test="reason != null">AND reason=#{reason}</if>
        """;
    String PROJECTION = """
        word_id,expected_word AS word,translation,reason,category,input_text AS last_input,mode,
        id AS training_id,batch_id,batch_item_id AS item_id,answered_at AS last_occurred_at,occurrence_count
        """;
    @Select("<script>"+ISSUES+" SELECT COUNT(*) FROM open_issues "+FILTER+"</script>")
    long count(@Param("userId") long userId,@Param("category") String category,@Param("reason") String reason);
    @Select("<script>"+ISSUES+" SELECT "+PROJECTION+" FROM open_issues "+FILTER+" ORDER BY id DESC,reason LIMIT #{limit} OFFSET #{offset}</script>")
    List<ConsolidationIssueVO> list(@Param("userId") long userId,@Param("category") String category,@Param("reason") String reason,@Param("offset") long offset,@Param("limit") int limit);
    @Select("<script>"+ISSUES+"""
        SELECT word_id,expected_word AS word,translation,reason,category,input_text AS last_input,mode,
          id AS training_id,batch_id,batch_item_id AS item_id,answered_at AS last_occurred_at,occurrence_count
        FROM (SELECT o.*,ROW_NUMBER() OVER(PARTITION BY word_id ORDER BY id DESC,reason) AS word_rank
          FROM open_issues o
        """+FILTER+"""
          <if test="wordIds != null">AND word_id IN
            <foreach collection="wordIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          </if>
        ) selection WHERE word_rank=1 ORDER BY id DESC LIMIT #{limit}
        </script>
        """)
    List<ConsolidationIssueVO> candidates(@Param("userId") long userId,@Param("category") String category,@Param("reason") String reason,
        @Param("wordIds") List<Long> wordIds,@Param("limit") int limit);
    record ReasonCount(String reason,String category,long wordCount) {}
    @Select("<script>"+ISSUES+" SELECT reason,category,COUNT(DISTINCT word_id) AS word_count FROM open_issues GROUP BY reason,category ORDER BY reason</script>")
    List<ReasonCount> summary(@Param("userId") long userId);
}
