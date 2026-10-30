package com.wordtrail.learning.service;

import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.LearningRecordQuery;
import com.wordtrail.learning.vo.LearningRecordVO;
import com.wordtrail.security.CurrentUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class LearningRecordsService {
    private final CurrentUser currentUser;
    private final LearningContext context;
    private final NamedParameterJdbcTemplate jdbc;

    // Each branch is scoped to the authenticated user before being combined. Creation time
    // is stable even when an in-progress record is updated or a daily round is appended.
    private static final String UNION = """
        SELECT 'DAILY' AS record_type, b.id AS business_id, b.learning_date AS record_date,
          b.created_at, b.status AS raw_status,
          CASE WHEN b.status='IN_PROGRESS' AND b.learning_date<>:today THEN 'INCOMPLETE'
               ELSE b.status END AS display_status,
          CASE WHEN b.status='IN_PROGRESS' AND b.learning_date=:today THEN 1 ELSE 0 END AS can_continue,
          b.daily_target, NULL AS source, NULL AS book_id, NULL AS single_attempt
        FROM learning_batch b WHERE b.user_id=:userId AND b.task_type='DAILY'
        UNION ALL
        SELECT 'DICTATION', b.id, DATE(b.created_at), b.created_at, b.status,
          CASE WHEN b.status='IN_PROGRESS' AND b.study_mode<>'DICTATION' THEN 'INCOMPLETE'
               ELSE b.status END,
          CASE WHEN b.status='IN_PROGRESS' AND b.study_mode='DICTATION'
                AND EXISTS (SELECT 1 FROM learning_batch_item i WHERE i.batch_id=b.id AND i.learning_status<>'DONE')
               THEN 1 ELSE 0 END,
          NULL, NULL, b.book_id, CASE WHEN b.study_mode='DICTATION' THEN 1 ELSE 0 END
        FROM learning_batch b WHERE b.user_id=:userId AND b.task_type='PRACTICE' AND b.practice_mode='DICTATION'
        UNION ALL
        SELECT 'SENTENCE', s.id, DATE(s.created_at), s.created_at, s.status, s.status,
          CASE WHEN s.status='IN_PROGRESS'
                AND EXISTS (SELECT 1 FROM sentence_translation_item i WHERE i.session_id=s.id AND i.status<>'DONE')
               THEN 1 ELSE 0 END,
          NULL, s.selection_source, NULL, NULL
        FROM sentence_translation_session s WHERE s.user_id=:userId
        """;
    private static final String FILTER = """
        FROM (""" + UNION + """
        ) r WHERE (:type='ALL' OR r.record_type=:type)
          AND (:status='ALL' OR r.display_status=:status)
        """;

    private record Header(String type, long id, LocalDate date, LocalDateTime createdAt,
            String rawStatus, String displayStatus, boolean canContinue, Integer target,
            String source, Long bookId, Boolean singleAttempt) {}
    private record Counts(long total, long done, long skipped, long fresh, long review) {}

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public PageVO<LearningRecordVO> list(LearningRecordQuery query) {
        var parameters = new MapSqlParameterSource()
            .addValue("userId", currentUser.id()).addValue("today", context.today())
            .addValue("type", query.getType().name()).addValue("status", query.getStatus().name())
            .addValue("limit", query.getSize()).addValue("offset", ((long) query.getPage()-1)*query.getSize());
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) " + FILTER, parameters, Long.class));
        if (total == 0 || ((long)query.getPage()-1)*query.getSize() >= total)
            return new PageVO<>(query.getPage(), query.getSize(), total, List.of());
        var rows = jdbc.query("SELECT r.* " + FILTER + " ORDER BY r.created_at DESC, r.record_type ASC, r.business_id DESC LIMIT :limit OFFSET :offset",
            parameters, (rs, n) -> header(rs));
        var batches = rows.stream().filter(row -> !row.type().equals("SENTENCE")).map(Header::id).toList();
        var sessions = rows.stream().filter(row -> row.type().equals("SENTENCE")).map(Header::id).toList();
        var batchCounts = counts(batches, false);
        var sessionCounts = counts(sessions, true);
        var records = rows.stream().map(row -> {
            boolean daily = row.type().equals("DAILY");
            var count = (row.type().equals("SENTENCE") ? sessionCounts : batchCounts)
                .getOrDefault(row.id(), new Counts(0, 0, 0, 0, 0));
            return new LearningRecordVO(row.type(),row.id(),row.date(),row.createdAt(),row.rawStatus(),row.displayStatus(),
                row.canContinue(),count.done(),count.total(),daily?count.skipped():null,daily?count.fresh():null,
                daily?count.review():null,row.target(),daily && row.target()!=null ? count.done()>=row.target() : null,
                row.source(),row.bookId(),row.singleAttempt());
        }).toList();
        return new PageVO<>(query.getPage(),query.getSize(),total,records);
    }

    private Header header(ResultSet rs) throws SQLException {
        Object book=rs.getObject("book_id"), target=rs.getObject("daily_target"), single=rs.getObject("single_attempt");
        return new Header(rs.getString("record_type"),rs.getLong("business_id"),rs.getDate("record_date").toLocalDate(),
            rs.getTimestamp("created_at").toLocalDateTime(),rs.getString("raw_status"),rs.getString("display_status"),
            rs.getInt("can_continue")!=0,target==null?null:((Number)target).intValue(),rs.getString("source"),
            book==null?null:((Number)book).longValue(),single==null?null:((Number)single).intValue()!=0);
    }

    private Map<Long,Counts> counts(List<Long> ids, boolean sentence) {
        if(ids.isEmpty()) return Map.of();
        String sql = sentence ? """
            SELECT session_id AS parent_id, COUNT(*) AS total_count,
              SUM(CASE WHEN status='DONE' THEN 1 ELSE 0 END) AS done_count,
              0 AS skipped_count, 0 AS new_count, 0 AS review_count
            FROM sentence_translation_item WHERE session_id IN (:ids) GROUP BY session_id
            """ : """
            SELECT batch_id AS parent_id, COUNT(*) AS total_count,
              SUM(CASE WHEN learning_status='DONE' THEN 1 ELSE 0 END) AS done_count,
              SUM(CASE WHEN learning_status='SKIPPED' THEN 1 ELSE 0 END) AS skipped_count,
              SUM(CASE WHEN item_type='NEW' THEN 1 ELSE 0 END) AS new_count,
              SUM(CASE WHEN item_type='REVIEW' THEN 1 ELSE 0 END) AS review_count
            FROM learning_batch_item WHERE batch_id IN (:ids) GROUP BY batch_id
            """;
        return jdbc.query(sql, new MapSqlParameterSource("ids",ids), (rs,n) -> Map.entry(rs.getLong("parent_id"),
            new Counts(rs.getLong("total_count"),rs.getLong("done_count"),rs.getLong("skipped_count"),
                rs.getLong("new_count"),rs.getLong("review_count")))).stream()
            .collect(Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue));
    }
}
