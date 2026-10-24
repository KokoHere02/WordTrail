package com.wordtrail.sentence.mapper;
import com.mybatisflex.core.BaseMapper;
import com.wordtrail.sentence.entity.SentenceNote;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface SentenceNoteMapper extends BaseMapper<SentenceNote> {
    record Row(Long id,Long sentenceId,String englishText,boolean available,String content,Long attemptId,Long version,LocalDateTime updatedAt) {}
    String FILTER="""
        FROM user_sentence_note n JOIN sentence_question q ON q.id=n.sentence_id
        LEFT JOIN sentence_translation_attempt a ON a.id=n.attempt_id AND a.user_id=n.user_id
        LEFT JOIN sentence_translation_item i ON i.id=a.item_id
        WHERE n.user_id=#{userId} AND (TRIM(n.content)!='' OR EXISTS(SELECT 1 FROM user_sentence_note_tag t WHERE t.note_id=n.id))
        <if test="tag != null">AND EXISTS(SELECT 1 FROM user_sentence_note_tag t WHERE t.note_id=n.id AND t.error_tag=#{tag})</if>
        <if test="wordId != null">AND EXISTS(SELECT 1 FROM sentence_word sw WHERE sw.sentence_id=q.id AND sw.word_id=#{wordId})</if>
        <if test="difficulty != null">AND q.difficulty=#{difficulty}</if>
        """;
    @Select("<script>SELECT COUNT(*) "+FILTER+"</script>")
    long countNotes(@Param("userId") long userId,@Param("tag") String tag,@Param("wordId") Long wordId,@Param("difficulty") Integer difficulty);
    @Select("""
        <script>SELECT n.id,n.sentence_id,COALESCE(i.english_text,q.english_text) AS english_text,(q.status='ACTIVE') AS available,
        n.content,n.attempt_id,n.version,n.updated_at
        """+FILTER+" ORDER BY n.updated_at DESC,n.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<Row> notes(@Param("userId") long userId,@Param("tag") String tag,@Param("wordId") Long wordId,@Param("difficulty") Integer difficulty,@Param("offset") long offset,@Param("limit") int limit);
}
