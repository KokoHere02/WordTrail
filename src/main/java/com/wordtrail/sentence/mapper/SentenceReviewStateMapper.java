package com.wordtrail.sentence.mapper;
import com.mybatisflex.core.BaseMapper;
import com.wordtrail.sentence.entity.*;
import com.wordtrail.sentence.vo.SentenceLearningVO.DueQuestion;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface SentenceReviewStateMapper extends BaseMapper<SentenceReviewState> {
    String DUE="""
        FROM user_sentence_review_state r JOIN sentence_question q ON q.id=r.sentence_id
        WHERE r.user_id=#{userId} AND r.next_review_date &lt;= #{today} AND q.status='ACTIVE'
        AND NOT EXISTS(SELECT 1 FROM sentence_translation_item i JOIN sentence_translation_session s ON s.id=i.session_id
          WHERE s.user_id=r.user_id AND s.status='IN_PROGRESS' AND i.sentence_id=r.sentence_id AND i.status!='DONE')
        <if test="difficulty != null">AND q.difficulty=#{difficulty}</if>
        <if test="wordId != null">AND EXISTS(SELECT 1 FROM sentence_word sw WHERE sw.sentence_id=q.id AND sw.word_id=#{wordId})</if>
        """;
    @Select("<script>SELECT COUNT(*) "+DUE+"</script>")
    long countDue(@Param("userId") long userId,@Param("today") LocalDate today,@Param("difficulty") Integer difficulty,@Param("wordId") Long wordId);
    @Select("""
        <script>SELECT q.id AS sentence_id,q.english_text,q.difficulty,q.source,q.source_reference,
        r.review_level,r.last_assessment,r.next_review_date
        """+DUE+" ORDER BY r.next_review_date ASC,r.id ASC LIMIT #{limit} OFFSET #{offset}</script>")
    List<DueQuestion> due(@Param("userId") long userId,@Param("today") LocalDate today,@Param("difficulty") Integer difficulty,
            @Param("wordId") Long wordId,@Param("offset") long offset,@Param("limit") int limit);
    @Select("<script>SELECT q.* "+DUE+"""
        <if test="sentenceIds != null">AND q.id IN
        <foreach collection="sentenceIds" item="id" open="(" separator="," close=")">#{id}</foreach></if>
        ORDER BY r.next_review_date ASC,r.id ASC LIMIT #{limit}</script>
        """)
    List<SentenceQuestion> candidates(@Param("userId") long userId,@Param("today") LocalDate today,@Param("difficulty") Integer difficulty,
            @Param("wordId") Long wordId,@Param("sentenceIds") List<Long> sentenceIds,@Param("limit") int limit);
}
