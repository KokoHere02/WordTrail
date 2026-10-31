package com.wordtrail.book.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.book.dto.*;
import com.wordtrail.book.entity.WordBook;
import com.wordtrail.book.entity.WordBookItem;
import com.wordtrail.book.mapper.*;
import com.wordtrail.book.vo.*;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.security.UserWriteLock;
import com.wordtrail.word.mapper.WordMapper;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service
@RequiredArgsConstructor
public class BookService {
    private final WordBookMapper books;
    private final WordBookItemMapper items;
    private final CurrentUser currentUser;
    private final WordMapper words;
    private final UserWriteLock userWriteLock;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<BookVO> list(BookQuery request) {
        long userId = currentUser.id();
        var query = QueryWrapper.create()
            .where(column("owner_id").isNull().or(column("owner_id").eq(userId)))
            .orderBy(column("id").asc());
        if (request.getScope() == BookScope.SYSTEM) { query.and(column("owner_id").isNull()); }
        if (request.getScope() == BookScope.PERSONAL) { query.and(column("owner_id").eq(userId)); }
        if (request.getKeyword() != null && !request.getKeyword().isEmpty()) {
            query.and("name LIKE ? ESCAPE '!'", "%" + escapeLike(request.getKeyword()) + "%");
        }
        var page = books.paginate(request.getPage(), request.getSize(), query);
        var records = page.getRecords();
        if (records.isEmpty()) {
            return new PageVO<>(request.getPage(), request.getSize(), page.getTotalRow(), List.of());
        }
        // One grouped query for all books in the page, never one query per book.
        var progress = items.progressByBooks(records.stream().map(WordBook::getId).toList(), userId, now())
            .stream().collect(Collectors.toMap(WordBookItemMapper.BookProgress::bookId, value -> value));
        return new PageVO<>(request.getPage(), request.getSize(), page.getTotalRow(),
            records.stream().map(book -> view(book, progress.get(book.getId()))).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BookVO detail(long bookId) {
        long userId = currentUser.id();
        return summary(requireAccessible(bookId, userId), userId);
    }

    @Transactional
    public BookVO create(BookEditRequest request) {
        long userId = currentUser.id(); userWriteLock.lock(userId);
        var now = now(); var book = new WordBook();
        book.setOwnerId(userId); book.setName(request.name().strip()); book.setDescription(description(request.description()));
        book.setCreatedAt(now); book.setUpdatedAt(now);
        if (books.insertSelective(book) != 1) { throw new IllegalStateException("Could not create personal word book"); }
        return view(book, null);
    }

    @Transactional
    public BookVO update(long bookId, BookEditRequest request) {
        long userId = currentUser.id(); userWriteLock.lock(userId);
        var book = requireWritable(bookId, userId);
        String name = request.name().strip(), description = description(request.description());
        if (!Objects.equals(book.getName(), name) || !Objects.equals(book.getDescription(), description)) {
            book.setName(name); book.setDescription(description); book.setUpdatedAt(now());
            if (books.updateByQuery(book, false, QueryWrapper.create().where(column("id").eq(bookId))
                    .and(column("owner_id").eq(userId))) != 1) {
                throw new IllegalStateException("Could not update personal word book");
            }
        }
        return summary(book, userId);
    }

    @Transactional
    public BookWordsChangeVO addWords(long bookId, BookWordIdsRequest request) {
        return changeWords(bookId, request, true);
    }

    @Transactional
    public BookWordsChangeVO removeWords(long bookId, BookWordIdsRequest request) {
        return changeWords(bookId, request, false);
    }

    private BookWordsChangeVO changeWords(long bookId, BookWordIdsRequest request, boolean add) {
        long userId = currentUser.id(); userWriteLock.lock(userId);
        requireWritable(bookId, userId);
        var ids = new LinkedHashSet<>(request.wordIds());
        if (ids.isEmpty() || ids.size() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "每次只能操作 1～100 个不同单词");
        }
        if (words.selectCountByQuery(QueryWrapper.create().where(column("id").in(ids))) != ids.size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "WORD_NOT_FOUND", "部分单词不存在于词库，未修改词本");
        }
        var existing = items.selectListByQuery(QueryWrapper.create().where(column("book_id").eq(bookId))
            .and(column("word_id").in(ids))).stream().map(WordBookItem::getWordId).collect(Collectors.toSet());
        int changed;
        var now = now();
        if (add) {
            var added = ids.stream().filter(id -> !existing.contains(id)).toList(); changed = added.size();
            if (changed > 0) {
                long order = items.maxSortOrder(bookId);
                if (order > Integer.MAX_VALUE - changed) {
                    throw new BusinessException(HttpStatus.CONFLICT, "BOOK_ORDER_LIMIT", "词本排序序号已达上限，未修改词本");
                }
                var entries = new ArrayList<WordBookItem>();
                for (long id : added) {
                    var entry = new WordBookItem(); entry.setBookId(bookId); entry.setWordId(id);
                    entry.setSortOrder((int) ++order); entry.setCreatedAt(now); entries.add(entry);
                }
                if (items.insertBatch(entries) != changed) { throw new IllegalStateException("Could not collect all selected words"); }
            }
        } else {
            changed = existing.size();
            if (changed > 0 && items.deleteByQuery(QueryWrapper.create().where(column("book_id").eq(bookId))
                    .and(column("word_id").in(existing))) != changed) {
                throw new IllegalStateException("Could not remove all selected word associations");
            }
        }
        if (changed > 0) {
            var patch = new WordBook(); patch.setUpdatedAt(now);
            if (books.updateByQuery(patch, true, QueryWrapper.create().where(column("id").eq(bookId))
                    .and(column("owner_id").eq(userId))) != 1) {
                throw new IllegalStateException("Could not update word book timestamp");
            }
        }
        return new BookWordsChangeVO(ids.size(), changed, ids.size() - changed);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<BookWordVO> randomWords(long bookId, int count) {
        long userId = currentUser.id();
        requireAccessible(bookId, userId);
        return items.randomUnplannedWords(bookId, userId, count);
    }

    public WordBook requireAccessible(long bookId, long userId) {
        var book = books.selectOneByQuery(QueryWrapper.create().where(column("id").eq(bookId))
            .and(column("owner_id").isNull().or(column("owner_id").eq(userId))));
        if (book == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "词书不存在或无权访问");
        }
        return book;
    }

    private WordBook requireWritable(long bookId, long userId) {
        var book = books.selectOneByQuery(QueryWrapper.create().where(column("id").eq(bookId))
            .and(column("owner_id").isNull().or(column("owner_id").eq(userId))).forUpdate());
        if (book == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "词书不存在或无权访问"); }
        if (book.getOwnerId() == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "BOOK_READ_ONLY", "系统词书只读，请创建个人词本后收词");
        }
        return book;
    }

    private BookVO summary(WordBook book, long userId) {
        var progress = items.progressByBooks(List.of(book.getId()), userId, now());
        return view(book, progress.isEmpty() ? null : progress.getFirst());
    }

    private BookVO view(WordBook book, WordBookItemMapper.BookProgress progress) {
        return new BookVO(book.getId(), book.getName(), book.getDescription(), progress == null ? 0 : progress.wordCount(),
            progress == null ? 0 : progress.unlearnedCount(), progress == null ? 0 : progress.startedCount(),
            progress == null ? 0 : progress.dueCount(), book.getOwnerId() == null ? "SYSTEM" : "PERSONAL", book.getOwnerId() != null);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai")).withNano(0); }
    private String description(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    private String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
