package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.WordReviewLog;
import com.wordtrail.learning.vo.WeakWordVO;
import com.wordtrail.learning.vo.ResultWordVO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Evidence is scoped to the authenticated user and one task type before aggregation. */
public interface ReviewEvidenceMapper extends BaseMapper<WordReviewLog> {
    record Coverage(long weakCount, long answerCount, long incompleteWordCount,
            LocalDateTime firstRecordedAt, LocalDateTime lastRecordedAt) {}

    // Inline derived tables keep each parameter occurrence explicitly bound on MySQL and H2.
    String ITEMS = """
        SELECT i.* FROM learning_batch b JOIN learning_batch_item i ON i.batch_id=b.id
        WHERE b.user_id=#{userId} AND b.task_type=#{source} AND b.practice_mode='RECOGNITION'
        <if test="batchId != null">AND b.id=#{batchId}</if>
        """;
    String ANSWERS = """
        SELECT a.id,a.batch_item_id,a.word_id,a.learning_date,a.answered_at,
               COALESCE((SELECT c.result FROM word_review_log c
                 WHERE c.user_id=a.user_id AND c.batch_id=a.batch_id AND c.batch_item_id=a.batch_item_id
                   AND c.corrects_log_id=a.id AND c.event_type='CORRECTION'
                 ORDER BY c.id DESC LIMIT 1),a.result) AS effective_result
        FROM word_review_log a JOIN learning_batch b ON b.id=a.batch_id AND b.user_id=a.user_id
        JOIN learning_batch_item i ON i.id=a.batch_item_id AND i.batch_id=b.id AND i.word_id=a.word_id
        WHERE a.user_id=#{userId} AND b.task_type=#{source} AND b.practice_mode='RECOGNITION' AND a.event_type='ANSWER'
        <if test="batchId != null">AND b.id=#{batchId}</if>
        """;
    String RANKED = """
        SELECT a.*,
               ROW_NUMBER() OVER(PARTITION BY word_id ORDER BY answered_at DESC,id DESC) AS word_rank,
               ROW_NUMBER() OVER(PARTITION BY batch_item_id ORDER BY answered_at DESC,id DESC) AS item_rank,
               COUNT(*) OVER(PARTITION BY word_id) AS word_answers,
               COUNT(*) OVER(PARTITION BY batch_item_id) AS item_answers,
               MIN(answered_at) OVER(PARTITION BY batch_item_id) AS first_answered_at
        FROM (
        """ + ANSWERS + ") a ";
    String COMPLETE = """
        CASE WHEN i.answer_count=COALESCE(a.item_answers,0)
          AND (#{source}='PRACTICE' OR i.study_attempt_count=(SELECT COUNT(*) FROM word_training_attempt t
            JOIN learning_batch tb ON tb.id=t.batch_id AND tb.user_id=t.user_id AND tb.learning_date=t.learning_date
            WHERE t.user_id=#{userId} AND t.batch_item_id=i.id AND t.batch_id=i.batch_id AND t.word_id=i.word_id))
          AND ((a.id IS NOT NULL AND i.result=a.effective_result)
            OR (a.id IS NULL AND i.result IS NULL AND i.answered_at IS NULL
                AND i.learning_status IN ('PENDING','SKIPPED')))
        THEN 1 ELSE 0 END
        """;
    String ITEM_FACTS = "SELECT i.*,COALESCE(a.item_answers,0) AS recorded_answers," + COMPLETE
        + " AS statistics_complete FROM (" + ITEMS + ") i LEFT JOIN (" + RANKED
        + ") a ON a.batch_item_id=i.id AND a.item_rank=1 ";

    @Select("""
        <script>
        SELECT COUNT(DISTINCT CASE WHEN a.word_rank=1 AND a.effective_result IN (2,3) THEN i.word_id END) AS weak_count,
               COALESCE(SUM(a.item_answers),0) AS answer_count,
               COUNT(DISTINCT CASE WHEN (
        """ + COMPLETE + """
               )=0 THEN i.word_id END) AS incomplete_word_count,
               MIN(a.first_answered_at) AS first_recorded_at,MAX(a.answered_at) AS last_recorded_at
        FROM (
        """ + ITEMS + ") i LEFT JOIN (" + RANKED + ") a ON a.batch_item_id=i.id AND a.item_rank=1 </script>")
    Coverage coverage(@Param("userId") long userId, @Param("source") String source, @Param("batchId") Long batchId);

    @Select("""
        <script>
        SELECT a.word_id,w.word,w.phonetic,w.translation,#{source} AS source,
               a.effective_result AS last_result,a.id AS last_answer_log_id,a.learning_date,
               a.answered_at AS last_answered_at,a.word_answers AS answer_count,
               CASE WHEN p.complete=1 THEN TRUE ELSE FALSE END AS statistics_complete
        FROM (
        """ + RANKED + """
        ) a JOIN word w ON w.id=a.word_id
        JOIN (SELECT word_id,MIN(statistics_complete) AS complete FROM (
        """ + ITEM_FACTS + """
        ) facts GROUP BY word_id) p
          ON p.word_id=a.word_id
        WHERE a.word_rank=1 AND a.effective_result IN (2,3)
        ORDER BY a.answered_at DESC,a.id DESC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<WeakWordVO> weakWords(@Param("userId") long userId, @Param("source") String source,
            @Param("batchId") Long batchId, @Param("offset") long offset, @Param("limit") int limit);

    @Select("<script>SELECT word_id FROM (" + RANKED + """
        ) ranked WHERE word_rank=1 AND effective_result IN (2,3) AND word_id IN
        <foreach collection="wordIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<Long> weakSelection(@Param("userId") long userId, @Param("source") String source,
            @Param("batchId") Long batchId, @Param("wordIds") List<Long> wordIds);

    @Select("""
        <script>
        SELECT i.id AS item_id,i.word_id,w.word,w.phonetic,w.translation,i.result,i.answered_at,
               i.recorded_answers AS answer_count,
               CASE WHEN i.statistics_complete=1 THEN TRUE ELSE FALSE END AS statistics_complete
        FROM (
        """ + ITEM_FACTS + """
        ) i JOIN word w ON w.id=i.word_id
        <if test="result != null">WHERE i.result=#{result}</if>
        ORDER BY i.sort_order ASC,i.id ASC LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<ResultWordVO> resultWords(@Param("userId") long userId, @Param("source") String source,
            @Param("batchId") Long batchId, @Param("result") Integer result,
            @Param("offset") long offset, @Param("limit") int limit);
}
