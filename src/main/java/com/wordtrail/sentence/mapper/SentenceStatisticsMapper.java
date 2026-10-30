package com.wordtrail.sentence.mapper;
import com.wordtrail.sentence.vo.SentenceLearningVO.*;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface SentenceStatisticsMapper {
    @Select("""
        SELECT COUNT(*) AS submission_count,
        COALESCE(SUM(CASE WHEN action='ANSWER' THEN 1 ELSE 0 END),0) AS answer_count,
        COALESCE(SUM(CASE WHEN action='REVEAL' THEN 1 ELSE 0 END),0) AS reveal_count,
        COALESCE(SUM(CASE WHEN self_assessment IS NOT NULL THEN 1 ELSE 0 END),0) AS self_review_count
        FROM sentence_translation_attempt WHERE user_id=#{userId}
        """)
    Totals totals(@Param("userId") long userId);
    @Select("""
        SELECT d FROM (SELECT learning_date AS d FROM sentence_translation_attempt WHERE user_id=#{userId}
          UNION SELECT review_date AS d FROM sentence_translation_attempt WHERE user_id=#{userId} AND self_assessment IS NOT NULL) dates
        ORDER BY d
        """)
    List<LocalDate> learningDates(@Param("userId") long userId);
    @Select("""
        SELECT d AS learning_date,SUM(submissions) AS submission_count,SUM(answers) AS answer_count,
          SUM(reveals) AS reveal_count,SUM(reviews) AS self_review_count FROM (
        SELECT learning_date AS d,1 AS submissions,CASE WHEN action='ANSWER' THEN 1 ELSE 0 END AS answers,
          CASE WHEN action='REVEAL' THEN 1 ELSE 0 END AS reveals,0 AS reviews
          FROM sentence_translation_attempt WHERE user_id=#{userId} AND learning_date BETWEEN #{from} AND #{to}
        UNION ALL
        SELECT review_date AS d,0 AS submissions,0 AS answers,0 AS reveals,1 AS reviews
          FROM sentence_translation_attempt WHERE user_id=#{userId} AND self_assessment IS NOT NULL AND review_date BETWEEN #{from} AND #{to}
        ) events GROUP BY d ORDER BY d
        """)
    List<Day> days(@Param("userId") long userId,@Param("from") LocalDate from,@Param("to") LocalDate to);
}
