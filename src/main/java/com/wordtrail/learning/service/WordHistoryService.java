package com.wordtrail.learning.service;

import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.WordHistoryQuery;
import com.wordtrail.learning.mapper.WordReviewLogMapper;
import com.wordtrail.learning.vo.WordHistoryVO;
import com.wordtrail.security.CurrentUser;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class WordHistoryService {
    private final CurrentUser currentUser;
    private final WordReviewLogMapper logs;
    private final ObjectMapper json;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<WordHistoryVO> history(long wordId, WordHistoryQuery query) {
        long userId = currentUser.id();
        long count = logs.countHistory(userId, wordId);
        long offset = ((long) query.getPage() - 1) * query.getSize();
        var records = offset >= count ? List.<WordHistoryVO>of() : logs.history(userId, wordId, offset, query.getSize()).stream()
            .map(row -> {
                var before = snapshot(row.scheduleBefore()); var after = snapshot(row.scheduleAfter());
                return new WordHistoryVO(row.logId(), row.batchId(), row.itemId(), row.learningDate(), row.answeredAt(),
                    row.result(), row.taskType(), row.eventType(), row.correctsLogId(), row.attemptNo(), row.affectsSchedule(),
                    before, after, before != null && after != null);
            }).toList();
        return new PageVO<>(query.getPage(), query.getSize(), count, records);
    }

    private JsonNode snapshot(String value) {
        if (value == null) { return null; }
        try {
            var node = json.readTree(value);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException ex) { return null; }
    }
}
