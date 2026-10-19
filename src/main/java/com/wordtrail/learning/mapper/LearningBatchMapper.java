package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.LearningBatch;
import com.wordtrail.learning.vo.BatchListVO;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface LearningBatchMapper extends BaseMapper<LearningBatch> {
    @Select("""
        <script>
        SELECT b.id AS batch_id, b.book_id, wb.name AS book_name, b.selection_type, b.status,
               COALESCE(p.total_count, 0) AS total_count,
               COALESCE(p.answered_count, 0) AS answered_count,
               pending.id AS next_item_id, b.created_at, b.updated_at
        FROM learning_batch b
        LEFT JOIN word_book wb ON wb.id = b.book_id
        LEFT JOIN (
            SELECT batch_id, COUNT(*) AS total_count, COUNT(result) AS answered_count,
                   MIN(CASE WHEN result IS NULL THEN sort_order END) AS next_sort_order
            FROM learning_batch_item
            WHERE batch_id IN
            <foreach collection="batchIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY batch_id
        ) p ON p.batch_id = b.id
        LEFT JOIN learning_batch_item pending
            ON pending.batch_id = b.id AND pending.sort_order = p.next_sort_order
        WHERE b.user_id = #{userId} AND b.id IN
        <foreach collection="batchIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY b.created_at DESC, b.id DESC
        </script>
        """)
    List<BatchListVO> progressForPage(@Param("userId") long userId, @Param("batchIds") List<Long> batchIds);
}
