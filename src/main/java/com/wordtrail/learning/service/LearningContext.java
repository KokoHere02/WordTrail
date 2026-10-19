package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.learning.entity.*;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.user.mapper.UserMapper;
import com.wordtrail.security.JwtTokenService;
import java.time.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import static com.mybatisflex.core.query.QueryMethods.column;

@Component
@RequiredArgsConstructor
public class LearningContext {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Clock clock;
    private final UserMapper users;
    private final LearningBatchMapper batches;
    private final LearningBatchItemMapper items;

    public LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZONE).withNano(0); }
    public LocalDate today() { return now().toLocalDate(); }

    // All learning mutations take the user lock first, including plan edits and practice.
    public void lockUser(long userId) {
        if (users.selectOneByQuery(QueryWrapper.create().select(column("id"))
                .where(column("id").eq(userId)).forUpdate()) == null) { throw JwtTokenService.unauthorized(); }
    }

    public LearningBatch ownedBatch(long userId, long batchId, boolean lock) {
        var query = QueryWrapper.create().where(column("id").eq(batchId)).and(column("user_id").eq(userId));
        if (lock) { query.forUpdate(); }
        var batch = batches.selectOneByQuery(query);
        if (batch == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND", "学习批次不存在或无权访问"); }
        return batch;
    }

    public List<LearningBatchItem> entries(long batchId) {
        return items.selectListByQuery(QueryWrapper.create().where(column("batch_id").eq(batchId))
            .orderBy(column("sort_order").asc(), column("id").asc()));
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
