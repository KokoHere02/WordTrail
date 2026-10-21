package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.learning.entity.*;
import com.wordtrail.learning.mapper.WordReviewLogMapper;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;

@Component @RequiredArgsConstructor
public class ReviewEvents {
    private final WordReviewLogMapper logs;
    private final LearningContext context;

    public WordReviewLog findRequest(long userId, String requestId) {
        return requestId == null ? null : logs.selectOneByQuery(QueryWrapper.create()
            .where(column("user_id").eq(userId)).and(column("request_id").eq(requestId)));
    }

    public WordReviewLog request(long userId, String requestId, long batchId, long itemId,
            String eventType, int result, Long corrects) {
        if (requestId == null) { return null; }
        var event = findRequest(userId, requestId);
        if (event != null && (event.getBatchId() != batchId || event.getBatchItemId() != itemId
                || !eventType.equals(event.getEventType()) || event.getResult() != result
                || !Objects.equals(corrects, event.getCorrectsLogId()))) {
            throw conflict("REQUEST_ID_REUSED", "requestId 已用于其他请求");
        }
        return event;
    }

    public WordReviewLog latestAnswer(long userId) {
        return logs.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("event_type").eq("ANSWER")).orderBy(column("id").desc()).limit(1));
    }

    // Used only to initialize an unfinished pre-upgrade item, never to invent missing history.
    public List<Integer> effectiveItemAnswers(long userId, long itemId) {
        var effective = new LinkedHashMap<Long, Integer>();
        for (var event : logs.selectListByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
                .and(column("batch_item_id").eq(itemId)).orderBy(column("id").asc()))) {
            if ("ANSWER".equals(event.getEventType())) { effective.put(event.getId(), event.getResult()); }
            else if (effective.containsKey(event.getCorrectsLogId())) {
                effective.put(event.getCorrectsLogId(), event.getResult());
            }
        }
        return new ArrayList<>(effective.values());
    }

    public WordReviewLog append(long userId, LearningBatchItem item, String requestId,
            String eventType, Long corrects, int result, boolean affectsSchedule, String before, String after) {
        return append(userId, item, requestId, eventType, corrects, result, affectsSchedule, before, after, context.now());
    }

    public WordReviewLog append(long userId, LearningBatchItem item, String requestId,
            String eventType, Long corrects, int result, boolean affectsSchedule, String before, String after,
            LocalDateTime answeredAt) {
        int attempt;
        if (corrects != null) { attempt = logs.selectOneById(corrects).getAttemptNo(); }
        else {
            attempt = Math.toIntExact(logs.selectCountByQuery(QueryWrapper.create()
                .where(column("user_id").eq(userId)).and(column("word_id").eq(item.getWordId()))
                .and(column("learning_date").eq(answeredAt.toLocalDate())).and(column("event_type").eq("ANSWER")))) + 1;
        }
        var event = new WordReviewLog(); event.setUserId(userId); event.setWordId(item.getWordId());
        event.setBatchId(item.getBatchId()); event.setBatchItemId(item.getId()); event.setRequestId(requestId);
        event.setEventType(eventType); event.setCorrectsLogId(corrects); event.setResult(result);
        event.setLearningDate(answeredAt.toLocalDate()); event.setAttemptNo(attempt); event.setAffectsSchedule(affectsSchedule);
        event.setScheduleBefore(before); event.setScheduleAfter(after); event.setAnsweredAt(answeredAt);
        event.setCreatedAt(answeredAt);
        if (logs.insertSelective(event) != 1) { throw new IllegalStateException("Could not save learning event"); }
        return event;
    }
}
