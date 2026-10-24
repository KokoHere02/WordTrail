package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.DictationReviewLog;
import com.wordtrail.learning.vo.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface DictationMapper extends BaseMapper<DictationReviewLog> {
    record Candidate(Long wordId,String word,String translation) {}
    String LATEST = """
        SELECT l.* FROM dictation_review_log l JOIN learning_batch b ON b.id=l.batch_id AND b.user_id=l.user_id
        WHERE l.user_id=#{userId} AND b.practice_mode='DICTATION'
          AND NOT EXISTS(SELECT 1 FROM dictation_review_log newer WHERE newer.user_id=l.user_id AND newer.word_id=l.word_id AND newer.id>l.id)
        """;
    String NEEDS = "(l.item_status_after='RELEARNING' OR l.completion_reason_after='LIMIT_REACHED')";

    @Select("""
        <script>
        SELECT w.id AS word_id,w.word,w.translation FROM word w
        <choose><when test="source == 'PLAN'">
          JOIN user_word_state s ON s.word_id=w.id AND s.user_id=#{userId}
          WHERE s.plan_status='ACTIVE' AND s.first_learned_at IS NOT NULL
        </when><otherwise>
          JOIN (
        """ + LATEST + """
          ) l ON l.word_id=w.id WHERE
        """ + NEEDS + """
        </otherwise></choose>
        AND LENGTH(TRIM(w.word))>0 AND w.translation IS NOT NULL AND LENGTH(TRIM(w.translation))>0
        <if test="bookId != null">AND EXISTS(SELECT 1 FROM word_book_item bi WHERE bi.book_id=#{bookId} AND bi.word_id=w.id)</if>
        <if test="wordIds != null">AND w.id IN
          <foreach collection="wordIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
        <choose><when test="source == 'PLAN'">ORDER BY s.last_learned_at DESC,s.id DESC</when>
        <otherwise>ORDER BY l.id DESC</otherwise></choose>
        LIMIT #{limit}
        </script>
        """)
    List<Candidate> candidates(@Param("userId") long userId,@Param("source") String source,
        @Param("bookId") Long bookId,@Param("wordIds") List<Long> wordIds,@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ("+LATEST+") l WHERE "+NEEDS)
    long countMistakes(@Param("userId") long userId);

    @Select("""
        SELECT l.batch_item_id AS item_id,l.word_id,i.dictation_target AS word,i.dictation_translation AS translation,
          l.verdict AS last_verdict,l.input_text AS last_input,l.attempt_no AS attempt_count,
          l.correct_streak_after AS correct_streak,l.item_status_after AS learning_status,
          l.completion_reason_after AS completion_reason,l.learning_date,l.answered_at
        FROM (
        """+LATEST+") l JOIN learning_batch_item i ON i.id=l.batch_item_id WHERE "+NEEDS+
        " ORDER BY l.answered_at DESC,l.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<DictationWordVO> mistakes(@Param("userId") long userId,@Param("offset") long offset,@Param("limit") int limit);

    String HISTORY = """
        FROM dictation_review_log l JOIN learning_batch b ON b.id=l.batch_id AND b.user_id=l.user_id
        JOIN learning_batch_item i ON i.id=l.batch_item_id AND i.batch_id=b.id
        WHERE l.user_id=#{userId} AND l.word_id=#{wordId} AND b.practice_mode='DICTATION'
        """;
    @Select("SELECT COUNT(*) "+HISTORY)
    long countHistory(@Param("userId") long userId,@Param("wordId") long wordId);
    @Select("""
        SELECT l.id AS log_id,l.batch_id,l.batch_item_id AS item_id,l.learning_date,l.answered_at,
          b.task_type,b.practice_mode,l.event_type AS action,l.input_text AS input,i.dictation_target AS expected_word,
          l.verdict,l.attempt_no,l.correct_streak_before,l.correct_streak_after,l.item_status_after,
          l.completion_reason_after AS completion_reason,l.affects_schedule
        """+HISTORY+" ORDER BY l.answered_at DESC,l.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<DictationHistoryVO> history(@Param("userId") long userId,@Param("wordId") long wordId,
        @Param("offset") long offset,@Param("limit") int limit);

    @Select("""
        SELECT i.id AS item_id,i.word_id,i.dictation_target AS word,i.dictation_translation AS translation,
          l.verdict AS last_verdict,l.input_text AS last_input,i.answer_count AS attempt_count,
          i.dictation_correct_streak AS correct_streak,i.learning_status,i.completion_reason,l.learning_date,l.answered_at
        FROM learning_batch_item i LEFT JOIN dictation_review_log l ON l.batch_item_id=i.id AND l.attempt_no=i.answer_count
        WHERE i.batch_id=#{batchId} ORDER BY i.sort_order ASC,i.id ASC LIMIT #{limit} OFFSET #{offset}
        """)
    List<DictationWordVO> resultWords(@Param("batchId") long batchId,@Param("offset") long offset,@Param("limit") int limit);
}
