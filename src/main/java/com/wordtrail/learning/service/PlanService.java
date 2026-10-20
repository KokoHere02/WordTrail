package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.book.service.BookService;
import com.wordtrail.book.mapper.WordBookItemMapper;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.entity.UserWordState;
import com.wordtrail.learning.mapper.UserWordStateMapper;
import com.wordtrail.learning.vo.*;
import com.wordtrail.security.CurrentUser;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service @RequiredArgsConstructor
public class PlanService {
    private final CurrentUser currentUser;
    private final LearningContext context;
    private final BookService books;
    private final WordBookItemMapper bookItems;
    private final UserWordStateMapper states;
    private final DailyTaskPlanSync dailyQueue;

    @Transactional
    public JoinPlanVO join(JoinPlanRequest request) {
        long userId = currentUser.id(); context.lockUser(userId);
        var now = context.now();
        books.requireAccessible(request.bookId(), userId);
        var ids = new LinkedHashSet<>(request.wordIds());
        if (ids.size() > 100) { throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "每次最多加入 100 个不同单词"); }
        var selected = bookItems.selectRequested(request.bookId(), new ArrayList<>(ids));
        if (selected.size() != ids.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "单词必须全部存在并属于指定词书");
        }
        var existing = states.selectListByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("word_id").in(ids))).stream().collect(Collectors.toMap(UserWordState::getWordId, s -> s));
        int added = 0, reactivated = 0;
        for (long id : selected) {
            var state = existing.get(id);
            if (state == null) {
                state = new UserWordState(); state.setUserId(userId); state.setWordId(id);
                state.setPlanStatus("ACTIVE"); state.setJoinedAt(now); state.setReviewLevel(0);
                if (states.insertSelective(state) != 1) { throw new IllegalStateException("Could not join learning plan"); }
                added++;
            } else if (!"ACTIVE".equals(state.getPlanStatus())) {
                var patch = new UserWordState(); patch.setId(state.getId()); patch.setPlanStatus("ACTIVE");
                if (states.update(patch) != 1) { throw new IllegalStateException("Could not reactivate learning plan"); }
                reactivated++;
            }
        }
        dailyQueue.apply(userId, ids, PlanStatus.ACTIVE, now);
        return new JoinPlanVO(ids.size(), added, reactivated, ids.size() - added - reactivated);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<PlanWordVO> list(PlanQuery query) {
        long userId = currentUser.id(); var now = context.now();
        String prefix = query.getKeyword() == null || query.getKeyword().isEmpty() ? null
            : query.getKeyword().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long count = states.countPlan(userId, query.getStatus().name(), query.getLearningStatus().name(), now, prefix);
        long offset = ((long) query.getPage() - 1) * query.getSize();
        var records = offset >= count ? List.<PlanWordVO>of() : states.planWords(userId,
            query.getStatus().name(), query.getLearningStatus().name(), now, prefix, query.getSortBy(),
            query.getDirection().name(), offset, query.getSize());
        return new PageVO<>(query.getPage(), query.getSize(), count, records);
    }

    @Transactional
    public void status(long wordId, PlanStatusRequest request) {
        changeStatus(Set.of(wordId), request.status());
    }

    @Transactional
    public BatchPlanStatusVO batchStatus(BatchPlanStatusRequest request) {
        var ids = new LinkedHashSet<>(request.wordIds());
        if (ids.size() > 100) { throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "每次只能操作 1～100 个不同单词"); }
        return changeStatus(ids, request.status());
    }

    private BatchPlanStatusVO changeStatus(Set<Long> ids, PlanStatus target) {
        long userId = currentUser.id(); context.lockUser(userId);
        var now = context.now();
        var selected = states.selectListByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("word_id").in(ids)));
        if (selected.size() != ids.size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PLAN_WORD_NOT_FOUND", "部分单词不在当前用户的计划中，未修改任何词");
        }
        var changed = new ArrayList<Long>();
        for (var state : selected) {
            String from = state.getPlanStatus();
            if (target.name().equals(from)) { continue; }
            boolean valid = target == PlanStatus.PAUSED && "ACTIVE".equals(from)
                || target == PlanStatus.ACTIVE && "PAUSED".equals(from)
                || target == PlanStatus.REMOVED && ("ACTIVE".equals(from) || "PAUSED".equals(from));
            if (!valid) { throw LearningContext.conflict("INVALID_PLAN_TRANSITION", "计划状态转换不允许，未修改任何词；移出后请通过加入计划重新加入"); }
            changed.add(state.getWordId());
        }
        if (!changed.isEmpty()) {
            var patch = new UserWordState(); patch.setPlanStatus(target.name());
            if (states.updateByQuery(patch, true, QueryWrapper.create().where(column("user_id").eq(userId))
                    .and(column("word_id").in(changed))) != changed.size()) {
                throw new IllegalStateException("Could not update all selected plan words");
            }
        }
        dailyQueue.apply(userId, ids, target, now);
        return new BatchPlanStatusVO(ids.size(), changed.size(), ids.size() - changed.size(), target);
    }
}
