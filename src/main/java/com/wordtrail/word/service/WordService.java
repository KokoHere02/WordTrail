package com.wordtrail.word.service;

import com.wordtrail.book.service.BookService;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.word.dto.WordSearchQuery;
import com.wordtrail.word.mapper.WordMapper;
import com.wordtrail.word.vo.DictionaryWordVO;
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
