package com.wordtrail.word.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.word.entity.Word;
import com.wordtrail.word.vo.DictionaryWordVO;
import com.wordtrail.word.vo.WordDetailVO;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface WordMapper extends BaseMapper<Word> {
    record ImportMatch(Long id,String word,String phonetic,String translation,boolean inBook,long candidateCount) {}

    @Select("""
        <script>
        SELECT id,word,phonetic,translation,in_book,candidate_count FROM (
          SELECT w.id,w.word,w.phonetic,w.translation,
                 CASE WHEN bi.id IS NULL THEN FALSE ELSE TRUE END AS in_book,
                 COUNT(*) OVER(PARTITION BY w.word) AS candidate_count,
                 ROW_NUMBER() OVER(PARTITION BY w.word ORDER BY w.id ASC) AS candidate_rank
          FROM word w LEFT JOIN word_book_item bi ON bi.word_id=w.id AND bi.book_id=#{bookId}
          WHERE w.word IN
          <foreach collection="terms" item="term" open="(" separator="," close=")">#{term}</foreach>
        ) matches WHERE candidate_rank &lt;= 20 ORDER BY word ASC,id ASC
        </script>
        """)
    List<ImportMatch> importMatches(@Param("bookId") long bookId,@Param("terms") List<String> terms);

    @Select("""
        SELECT b.id,b.name FROM word_book b JOIN word_book_item i ON i.book_id=b.id
        WHERE b.owner_id=#{userId} AND i.word_id=#{wordId} ORDER BY b.id ASC
        """)
    List<WordDetailVO.PersonalBook> personalBooks(@Param("userId") long userId, @Param("wordId") long wordId);

    String SEARCH_FROM = """
        FROM word w WHERE
        <choose><when test="matchType == 'EXACT'">w.word = #{keyword}</when>
        <otherwise>w.word LIKE #{prefix} ESCAPE '!'</otherwise></choose>
        """;

    @Select("<script>SELECT COUNT(*) " + SEARCH_FROM + "</script>")
    long countSearch(@Param("keyword") String keyword, @Param("prefix") String prefix, @Param("matchType") String matchType);

    @Select("""
        <script>SELECT w.id, w.word, w.phonetic, w.translation,
        <choose><when test="bookId != null">
          CASE WHEN EXISTS(SELECT 1 FROM word_book_item bi WHERE bi.book_id=#{bookId} AND bi.word_id=w.id)
               THEN TRUE ELSE FALSE END
        </when><otherwise>NULL</otherwise></choose> AS in_book
        """ + SEARCH_FROM + " ORDER BY w.word ASC, w.id ASC LIMIT #{limit} OFFSET #{offset}</script>")
    List<DictionaryWordVO> search(@Param("keyword") String keyword, @Param("prefix") String prefix,
            @Param("matchType") String matchType, @Param("bookId") Long bookId,
            @Param("offset") long offset, @Param("limit") int limit);
}
