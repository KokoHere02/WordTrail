package com.wordtrail.word.service;

import com.wordtrail.book.service.BookService;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.word.dto.WordSearchQuery;
import com.wordtrail.word.mapper.WordMapper;
import com.wordtrail.word.vo.DictionaryWordVO;
import com.wordtrail.word.vo.WordDetailVO;
import com.wordtrail.learning.mapper.UserWordStateMapper;
import com.wordtrail.learning.service.LearningContext;
import com.wordtrail.common.exception.BusinessException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.http.HttpStatus;
import static com.mybatisflex.core.query.QueryMethods.column;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WordService {
    private final WordMapper words;
    private final BookService books;
    private final CurrentUser currentUser;
    private final UserWordStateMapper states;
    private final LearningContext context;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WordDetailVO detail(long wordId) {
        long userId = currentUser.id();
        var word = words.selectOneById(wordId);
        if (word == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "WORD_NOT_FOUND", "单词不存在"); }
        var state = states.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("word_id").eq(wordId)));
        var plan = state == null ? null : new WordDetailVO.Plan(state.getPlanStatus(), state.getJoinedAt(),
            state.getFirstLearnedAt(), state.getLastLearnedAt(), state.getLastResult(), state.getNextReviewAt(),
            state.getReviewLevel(), state.getFirstLearnedAt()!=null,
            "ACTIVE".equals(state.getPlanStatus()) && state.getFirstLearnedAt()!=null
                && state.getNextReviewAt()!=null && !state.getNextReviewAt().isAfter(context.now()));
        return new WordDetailVO(word.getId(), word.getWord(), word.getPhonetic(), word.getTranslation(),
            words.personalBooks(userId, wordId), plan);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<DictionaryWordVO> search(WordSearchQuery query) {
        long userId = currentUser.id();
        if (query.getBookId() != null) { books.requireAccessible(query.getBookId(), userId); }
        String keyword = query.getKeyword().strip();
        String prefix = keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long count = words.countSearch(keyword, prefix, query.getMatchType().name());
        long offset = ((long) query.getPage() - 1) * query.getSize();
        var records = offset >= count ? List.<DictionaryWordVO>of() : words.search(keyword, prefix,
            query.getMatchType().name(), query.getBookId(), offset, query.getSize());
        return new PageVO<>(query.getPage(), query.getSize(), count, records);
    }
}
