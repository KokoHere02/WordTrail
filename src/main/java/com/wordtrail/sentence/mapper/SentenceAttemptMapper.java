package com.wordtrail.sentence.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.sentence.entity.SentenceTranslationAttempt;
import com.wordtrail.sentence.vo.SentenceTranslationVO.*;
import com.wordtrail.sentence.vo.SentenceLearningVO.ActivityDetail;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface SentenceAttemptMapper extends BaseMapper<SentenceTranslationAttempt> {
    String HISTORY="""
        FROM sentence_translation_attempt a JOIN sentence_translation_item i
          ON i.id=a.item_id AND i.session_id=a.session_id AND i.sentence_id=a.sentence_id
        WHERE a.user_id=#{userId}
        <if test="sentenceId != null">AND a.sentence_id=#{sentenceId}</if>
        <if test="sessionId != null">AND a.session_id=#{sessionId}</if>
        """;
    @Select("<script>SELECT COUNT(*) "+HISTORY+"</script>")
    long countHistory(@Param("userId") long userId,@Param("sentenceId") Long sentenceId,@Param("sessionId") Long sessionId);
    @Select("""
        <script>SELECT a.id AS attempt_id,a.session_id,a.item_id,a.sentence_id,i.english_text,a.action,a.translation,
          i.reference_translation,i.explanation,a.self_assessment,a.learning_date,a.submitted_at,a.review_date,a.reviewed_at
        """+HISTORY+" ORDER BY a.submitted_at DESC,a.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<History> history(@Param("userId") long userId,@Param("sentenceId") Long sentenceId,@Param("sessionId") Long sessionId,
        @Param("offset") long offset,@Param("limit") int limit);

    String DATE_ACTIVITY = """
        FROM sentence_translation_attempt a JOIN sentence_translation_item i
          ON i.id=a.item_id AND i.session_id=a.session_id AND i.sentence_id=a.sentence_id
        WHERE a.user_id=#{userId} AND (a.learning_date=#{date}
          OR (a.self_assessment IS NOT NULL AND a.review_date=#{date}))
        """;
    @Select("SELECT COUNT(*) " + DATE_ACTIVITY)
    long countDateActivity(@Param("userId") long userId,@Param("date") LocalDate date);
    @Select("""
        SELECT a.id AS attempt_id,a.session_id,a.item_id,a.sentence_id,i.english_text,a.action,a.translation,
          i.reference_translation,i.explanation,a.self_assessment,a.learning_date,a.submitted_at,a.review_date,a.reviewed_at,
          CASE WHEN a.learning_date=#{date} THEN TRUE ELSE FALSE END AS submitted_on_date,
          CASE WHEN a.self_assessment IS NOT NULL AND a.review_date=#{date} THEN TRUE ELSE FALSE END AS reviewed_on_date,
          CASE WHEN a.self_assessment IS NOT NULL AND a.review_date=#{date} THEN a.reviewed_at ELSE a.submitted_at END AS activity_at
        """ + DATE_ACTIVITY + """
        ORDER BY activity_at DESC,a.id DESC LIMIT #{limit} OFFSET #{offset}
        """)
    List<ActivityDetail> dateActivity(@Param("userId") long userId,@Param("date") LocalDate date,
        @Param("offset") long offset,@Param("limit") int limit);

    String REVIEW="""
        FROM sentence_translation_attempt a JOIN sentence_question q ON q.id=a.sentence_id
        JOIN sentence_translation_item i ON i.id=a.item_id
        WHERE a.user_id=#{userId} AND a.self_assessment IN ('PARTIAL','NOT_UNDERSTOOD')
          AND NOT EXISTS(SELECT 1 FROM sentence_translation_attempt newer WHERE newer.user_id=a.user_id
            AND newer.sentence_id=a.sentence_id AND newer.self_assessment IS NOT NULL AND newer.id>a.id)
        """;
    @Select("SELECT COUNT(*) "+REVIEW)
    long countReviewQueue(@Param("userId") long userId);
    @Select("""
        SELECT a.sentence_id,i.english_text,i.difficulty,(q.status='ACTIVE') AS available,
          a.id AS attempt_id,a.self_assessment,a.reviewed_at
        """+REVIEW+
        " ORDER BY a.reviewed_at DESC,a.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<ReviewWord> reviewQueue(@Param("userId") long userId,@Param("offset") long offset,@Param("limit") int limit);
}
