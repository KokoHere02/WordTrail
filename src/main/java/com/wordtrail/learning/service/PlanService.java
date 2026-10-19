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

    @Transactional
    public JoinPlanVO join(JoinPlanRequest request) {
        long userId = currentUser.id(); context.lockUser(userId);
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
                state.setPlanStatus("ACTIVE"); state.setJoinedAt(context.now()); state.setReviewLevel(0);
                if (states.insertSelective(state) != 1) { throw new IllegalStateException("Could not join learning plan"); }
                added++;
            } else if (!"ACTIVE".equals(state.getPlanStatus())) {
                var patch = new UserWordState(); patch.setId(state.getId()); patch.setPlanStatus("ACTIVE");
                if (states.update(patch) != 1) { throw new IllegalStateException("Could not reactivate learning plan"); }
                reactivated++;
            }
        }
        return new JoinPlanVO(ids.size(), added, reactivated, ids.size() - added - reactivated);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<PlanWordVO> list(PlanQuery query) {
        long userId = currentUser.id(); var now = context.now();
        long count = states.countPlan(userId, query.getStatus().name(), query.getLearningStatus().name(), now);
        long offset = ((long) query.getPage() - 1) * query.getSize();
        var records = offset >= count ? List.<PlanWordVO>of() : states.planWords(userId,
            query.getStatus().name(), query.getLearningStatus().name(), now, offset, query.getSize());
        return new PageVO<>(query.getPage(), query.getSize(), count, records);
    }

    @Transactional
    public void status(long wordId, PlanStatusRequest request) {
        long userId = currentUser.id(); context.lockUser(userId);
        var state = states.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("word_id").eq(wordId)));
        if (state == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "PLAN_WORD_NOT_FOUND", "计划中不存在该单词"); }
        if (request.status().name().equals(state.getPlanStatus())) { return; }
        var patch = new UserWordState(); patch.setId(state.getId()); patch.setPlanStatus(request.status().name());
        if (states.update(patch) != 1) { throw new IllegalStateException("Could not update learning plan"); }
    }
}
