package com.wordtrail.sentence.mapper;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface SentenceWordBridgeMapper {
    record WordCount(Long wordId,long sentenceCount) {}
    @Select("""
        <script>SELECT sw.word_id,COUNT(*) AS sentence_count FROM sentence_word sw JOIN sentence_question q ON q.id=sw.sentence_id
        WHERE q.status='ACTIVE' AND sw.word_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY sw.word_id</script>
        """)
    List<WordCount> counts(@Param("ids") List<Long> ids);
    @Select("""
        <script>SELECT DISTINCT q.id FROM sentence_question q JOIN sentence_word sw ON sw.sentence_id=q.id
        WHERE q.status='ACTIVE' AND sw.word_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        <if test="difficulty != null">AND q.difficulty=#{difficulty}</if> ORDER BY q.id LIMIT #{limit}</script>
        """)
    List<Long> candidates(@Param("ids") List<Long> ids,@Param("difficulty") Integer difficulty,@Param("limit") int limit);
}
