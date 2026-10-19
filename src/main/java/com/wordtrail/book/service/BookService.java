package com.wordtrail.book.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.book.dto.*;
import com.wordtrail.book.entity.WordBook;
import com.wordtrail.book.mapper.*;
import com.wordtrail.book.vo.*;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.security.CurrentUser;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service
@RequiredArgsConstructor
public class BookService {
    private final WordBookMapper books;
    private final WordBookItemMapper items;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public PageVO<BookVO> list(BookQuery request) {
        long userId = currentUser.id();
        var query = QueryWrapper.create()
            .where(column("owner_id").isNull().or(column("owner_id").eq(userId)))
            .orderBy(column("id").asc());
        if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
            query.and("name LIKE ? ESCAPE '!'", "%" + escapeLike(request.getKeyword()) + "%");
        }
        var page = books.paginate(request.getPage(), request.getSize(), query);
        var records = page.getRecords();
        if (records.isEmpty()) {
            return new PageVO<>(request.getPage(), request.getSize(), page.getTotalRow(), List.of());
        }
        // One grouped query for all books in the page, never one query per book.
        Map<Long, Long> counts = items.countByBooks(records.stream().map(WordBook::getId).toList())
            .stream().collect(Collectors.toMap(WordBookItemMapper.BookCount::bookId,
                WordBookItemMapper.BookCount::wordCount));
        return new PageVO<>(request.getPage(), request.getSize(), page.getTotalRow(),
            records.stream().map(book -> new BookVO(book.getId(), book.getName(), book.getDescription(),
                counts.getOrDefault(book.getId(), 0L))).toList());
    }

    @Transactional(readOnly = true)
    public PageVO<BookWordVO> words(long bookId, BookWordsQuery request) {
        long userId = currentUser.id();
        requireAccessible(bookId, userId);
        String prefix = request.getKeyword() == null || request.getKeyword().isEmpty()
            ? null : escapeLike(request.getKeyword()) + "%";
        String status = request.getLearningStatus().name();
        long total = items.countWords(bookId, userId, prefix, status);
        long offset = ((long) request.getPage() - 1) * request.getSize();
        var records = total == 0 || offset >= total ? List.<BookWordVO>of()
            : items.listWords(bookId, userId, prefix, status, offset, request.getSize());
        return new PageVO<>(request.getPage(), request.getSize(), total, records);
    }

    public WordBook requireAccessible(long bookId, long userId) {
        var book = books.selectOneByQuery(QueryWrapper.create().where(column("id").eq(bookId))
            .and(column("owner_id").isNull().or(column("owner_id").eq(userId))));
        if (book == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "词书不存在或无权访问");
        }
        return book;
    }

    private String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
