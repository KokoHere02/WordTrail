package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.LearningBatchItem;
import com.wordtrail.learning.vo.ResultWordVO;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface LearningBatchItemMapper extends BaseMapper<LearningBatchItem> {
    @Select("""
        <script>
        SELECT i.id AS item_id, i.word_id, w.word, w.phonetic, w.translation,
               i.result, i.answered_at
        FROM learning_batch_item i
        INNER JOIN word w ON w.id = i.word_id
        WHERE i.batch_id = #{batchId}
        <if test="result != null">AND i.result = #{result}</if>
        ORDER BY i.sort_order ASC, i.id ASC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<ResultWordVO> resultWords(@Param("batchId") long batchId, @Param("result") Integer result,
                                   @Param("offset") long offset, @Param("limit") int limit);
}
