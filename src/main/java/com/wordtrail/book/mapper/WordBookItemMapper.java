package com.wordtrail.book.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.book.entity.WordBookItem;
import com.wordtrail.book.vo.BookWordVO;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface WordBookItemMapper extends BaseMapper<WordBookItem> {
    record BookCount(Long bookId, long wordCount) {}

    @Select("""
        <script>
        SELECT book_id, COUNT(*) AS word_count FROM word_book_item
        WHERE book_id IN
        <foreach collection="bookIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY book_id
        </script>
        """)
    List<BookCount> countByBooks(@Param("bookIds") List<Long> bookIds);

    String WORDS_FROM = """
        FROM word_book_item bi
        INNER JOIN word w ON w.id = bi.word_id
        LEFT JOIN user_word_state s ON s.word_id = w.id AND s.user_id = #{userId}
        WHERE bi.book_id = #{bookId}
        <if test="prefix != null">AND w.word LIKE #{prefix} ESCAPE '!'</if>
        <if test="status == 'UNLEARNED'">AND s.first_learned_at IS NULL</if>
        <if test="status == 'LEARNED'">AND s.first_learned_at IS NOT NULL</if>
        """;

    @Select("<script>SELECT COUNT(*) " + WORDS_FROM + "</script>")
    long countWords(@Param("bookId") long bookId, @Param("userId") long userId,
                    @Param("prefix") String prefix, @Param("status") String status);

    @Select("""
        <script>
        SELECT w.id, w.word, w.phonetic, w.translation,
               CASE WHEN s.first_learned_at IS NULL THEN FALSE ELSE TRUE END AS learned, s.last_result
        """ + WORDS_FROM + """
        ORDER BY bi.sort_order ASC, bi.id ASC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<BookWordVO> listWords(@Param("bookId") long bookId, @Param("userId") long userId,
                              @Param("prefix") String prefix, @Param("status") String status,
                              @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
        SELECT w.id
        FROM word_book_item bi
        INNER JOIN word w ON w.id = bi.word_id
        LEFT JOIN user_word_state s ON s.word_id = w.id AND s.user_id = #{userId}
        WHERE bi.book_id = #{bookId} AND s.first_learned_at IS NULL
        ORDER BY bi.sort_order ASC, bi.id ASC
        LIMIT #{limit}
        """)
    List<Long> selectUnlearned(@Param("bookId") long bookId, @Param("userId") long userId,
                               @Param("limit") int limit);

    @Select("""
        <script>
        SELECT w.id FROM word_book_item bi
        INNER JOIN word w ON w.id = bi.word_id
        WHERE bi.book_id = #{bookId} AND w.id IN
        <foreach collection="wordIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY bi.sort_order ASC, bi.id ASC
        </script>
        """)
    List<Long> selectRequested(@Param("bookId") long bookId, @Param("wordIds") List<Long> wordIds);
}
