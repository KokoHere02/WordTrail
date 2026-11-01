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
    @Select("SELECT w.id AS word_id,w.word FROM sentence_word sw JOIN word w ON w.id=sw.word_id WHERE sw.sentence_id=#{sentenceId} ORDER BY sw.word_id ASC")
    List<LinkedWord> linkedWords(@Param("sentenceId") long sentenceId);
}
