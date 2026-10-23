package com.wordtrail.word.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.word.entity.Word;
import com.wordtrail.word.vo.DictionaryWordVO;
import com.wordtrail.word.vo.WordDetailVO;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface WordMapper extends BaseMapper<Word> {
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
