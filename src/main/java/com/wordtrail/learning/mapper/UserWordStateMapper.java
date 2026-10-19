package com.wordtrail.learning.mapper;

import com.mybatisflex.core.BaseMapper;
import com.wordtrail.learning.entity.UserWordState;
import com.wordtrail.learning.vo.PlanWordVO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface UserWordStateMapper extends BaseMapper<UserWordState> {
    String PLAN_FROM = """
        FROM user_word_state s INNER JOIN word w ON w.id = s.word_id
        WHERE s.user_id = #{userId} AND s.plan_status = #{status}
        <if test="learningStatus == 'NEW'">AND s.first_learned_at IS NULL</if>
        <if test="learningStatus == 'LEARNED'">AND s.first_learned_at IS NOT NULL</if>
        <if test="learningStatus == 'DUE'">
          AND s.plan_status = 'ACTIVE' AND s.first_learned_at IS NOT NULL AND s.next_review_at &lt;= #{now}
        </if>
        """;

    @Select("<script>SELECT COUNT(*) " + PLAN_FROM + "</script>")
    long countPlan(@Param("userId") long userId, @Param("status") String status,
                   @Param("learningStatus") String learningStatus, @Param("now") LocalDateTime now);

    @Select("""
        <script>SELECT s.word_id, w.word, w.phonetic, w.translation, s.plan_status, s.joined_at,
            s.first_learned_at, s.last_learned_at, s.last_result, s.next_review_at, s.review_level
        """ + PLAN_FROM + " ORDER BY s.joined_at ASC, s.id ASC LIMIT #{limit} OFFSET #{offset}</script>")
    List<PlanWordVO> planWords(@Param("userId") long userId, @Param("status") String status,
        @Param("learningStatus") String learningStatus, @Param("now") LocalDateTime now,
        @Param("offset") long offset, @Param("limit") int limit);
}
