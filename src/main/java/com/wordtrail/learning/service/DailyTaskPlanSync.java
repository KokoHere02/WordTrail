package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.learning.dto.PlanStatus;
import com.wordtrail.learning.mapper.LearningBatchItemMapper;
import com.wordtrail.learning.mapper.LearningBatchMapper;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.mybatisflex.core.query.QueryMethods.column;

@Component
@RequiredArgsConstructor
public class DailyTaskPlanSync {
    private final LearningContext context;
    private final LearningBatchMapper batches;
    private final LearningBatchItemMapper items;

    // Caller holds the user row lock and transaction; lock ordering remains user -> batch.
    public void apply(long userId, Set<Long> wordIds, PlanStatus target, LocalDateTime now) {
        var batch = batches.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("task_type").eq("DAILY")).and(column("learning_date").eq(now.toLocalDate())).forUpdate());
        if (batch == null || "ABANDONED".equals(batch.getStatus())) { return; }
        if (target == PlanStatus.ACTIVE && !"IN_PROGRESS".equals(batch.getStatus())) { return; }
        var entries = context.entries(batch.getId());
        boolean changed = false;
        for (var item : entries) {
            if (!wordIds.contains(item.getWordId())) { continue; }
            if (target != PlanStatus.ACTIVE && !"SKIPPED".equals(item.getLearningStatus())) {
                item.setStatusBeforeSkip(item.getLearningStatus());
                item.setLearningStatus("SKIPPED");
                item.setSkippedAt(now);
            } else if (target == PlanStatus.ACTIVE && "SKIPPED".equals(item.getLearningStatus())) {
                item.setLearningStatus(item.getStatusBeforeSkip());
                item.setStatusBeforeSkip(null);
                item.setSkippedAt(null);
            } else { continue; }
            // Preserve queue, feedback, attempts, and the original completed_at, if any.
            if (items.updateByQuery(item, false, QueryWrapper.create().where(column("id").eq(item.getId()))) != 1) {
                throw new IllegalStateException("Could not synchronize plan word with daily queue");
            }
            changed = true;
        }
        if (!changed) { return; }
        boolean finished = entries.stream().noneMatch(i -> "PENDING".equals(i.getLearningStatus()) || "RELEARNING".equals(i.getLearningStatus()));
        if (finished && "IN_PROGRESS".equals(batch.getStatus())) {
            batch.setStatus("COMPLETED"); batch.setCompletedAt(now);
        }
        batch.setUpdatedAt(now);
        if (batches.update(batch) != 1) { throw new IllegalStateException("Could not save daily queue progress"); }
    }
}
