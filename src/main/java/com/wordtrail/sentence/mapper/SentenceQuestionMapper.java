package com.wordtrail.sentence.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.sentence.entity.SentenceQuestion;
import com.wordtrail.sentence.vo.SentenceTranslationVO.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface SentenceQuestionMapper extends BaseMapper<SentenceQuestion> {
    record WordLink(Long sentenceId,Long wordId,String word) {}
    @Select("""
        <script>SELECT sw.sentence_id,w.id AS word_id,w.word FROM sentence_word sw JOIN word w ON w.id=sw.word_id
        WHERE sw.sentence_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY sw.sentence_id,sw.word_id</script>
        """)
    List<WordLink> linkedForQuestions(@Param("ids") List<Long> ids);
    String FILTER="""
        FROM sentence_question q WHERE q.status='ACTIVE'
        <if test="difficulty != null">AND q.difficulty=#{difficulty}</if>
        <if test="wordId != null">AND EXISTS(SELECT 1 FROM sentence_word sw WHERE sw.sentence_id=q.id AND sw.word_id=#{wordId})</if>
        <if test="source == 'FAVORITES'">AND EXISTS(SELECT 1 FROM user_sentence_favorite f WHERE f.user_id=#{userId} AND f.sentence_id=q.id)</if>
        <if test="source == 'REVIEW'">AND EXISTS(SELECT 1 FROM sentence_translation_attempt a
          WHERE a.user_id=#{userId} AND a.sentence_id=q.id AND a.self_assessment IN ('PARTIAL','NOT_UNDERSTOOD')
          AND NOT EXISTS(SELECT 1 FROM sentence_translation_attempt newer WHERE newer.user_id=a.user_id
            AND newer.sentence_id=a.sentence_id AND newer.self_assessment IS NOT NULL AND newer.id>a.id))</if>
        """;
    @Select("<script>SELECT COUNT(*) "+FILTER+"</script>")
    long countQuestions(@Param("userId") long userId,@Param("source") String source,
        @Param("difficulty") Integer difficulty,@Param("wordId") Long wordId);

    @Select("""
        <script>SELECT q.id AS sentence_id,q.english_text,q.difficulty,q.source,q.source_reference,
          EXISTS(SELECT 1 FROM user_sentence_favorite f WHERE f.user_id=#{userId} AND f.sentence_id=q.id) AS favorite
        """+FILTER+" ORDER BY q.id ASC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Question> questions(@Param("userId") long userId,@Param("source") String source,
        @Param("difficulty") Integer difficulty,@Param("wordId") Long wordId,@Param("offset") long offset,@Param("limit") int limit);

    @Select("<script>SELECT q.* "+FILTER+"""
        <if test="sentenceIds != null">AND q.id IN
          <foreach collection="sentenceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if> ORDER BY q.id ASC LIMIT #{limit}</script>
        """)
    List<SentenceQuestion> candidates(@Param("userId") long userId,@Param("source") String source,
        @Param("difficulty") Integer difficulty,@Param("wordId") Long wordId,
        @Param("sentenceIds") List<Long> sentenceIds,@Param("limit") int limit);

    @Select("SELECT w.id AS word_id,w.word FROM sentence_word sw JOIN word w ON w.id=sw.word_id WHERE sw.sentence_id=#{sentenceId} ORDER BY sw.word_id ASC")
    List<LinkedWord> linkedWords(@Param("sentenceId") long sentenceId);
}
