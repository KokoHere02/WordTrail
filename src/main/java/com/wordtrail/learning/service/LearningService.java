package com.wordtrail.learning.service;

import com.wordtrail.book.mapper.WordBookItemMapper;
import com.wordtrail.book.mapper.WordBookMapper;
import com.wordtrail.book.service.BookService;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.entity.*;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.learning.vo.*;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.user.mapper.UserMapper;
import com.wordtrail.word.entity.Word;
import com.wordtrail.word.mapper.WordMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service
@RequiredArgsConstructor
public class LearningService {
    private final BookService books;
    private final WordBookItemMapper bookItems;
    private final WordBookMapper wordBooks;
    private final LearningBatchMapper batches;
    private final LearningBatchItemMapper details;
    private final CurrentUser currentUser;
    private final UserMapper users;
    private final UserWordStateMapper states;
    private final WordMapper words;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<BatchListVO> list(BatchQuery request) {
        long userId = currentUser.id();
        var query = QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("status").eq(request.getStatus().name()))
            .orderBy(column("created_at").desc(), column("id").desc());
        var page = batches.paginate(request.getPage(), request.getSize(), query);
        var ids = page.getRecords().stream().map(LearningBatch::getId).toList();
        // Aggregate only this page's items in one query. Reads never touch updated_at.
        var records = ids.isEmpty() ? List.<BatchListVO>of() : batches.progressForPage(userId, ids);
        return new PageVO<>(request.getPage(), request.getSize(), page.getTotalRow(), records);
    }

    @Transactional(readOnly = true)
    public BatchProgressVO progress(long batchId) {
        var batch = ownedBatch(batchId, currentUser.id(), false);
        var counts = counts(entries(batchId));
        return new BatchProgressVO(batch.getId(), batch.getBookId(), batch.getSelectionType(),
            batch.getStatus(), counts.wordCount(), counts.answeredCount(), counts.nextItemId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BatchResultVO result(long batchId) {
        var batch = ownedBatch(batchId, currentUser.id(), false);
        requireCompleted(batch);
        var entries = entries(batchId);
        long known = entries.stream().filter(item -> Objects.equals(item.getResult(), 1)).count();
        long uncertain = entries.stream().filter(item -> Objects.equals(item.getResult(), 2)).count();
        long unknown = entries.stream().filter(item -> Objects.equals(item.getResult(), 3)).count();
        if (known + uncertain + unknown != entries.size()) {
            throw conflict("BATCH_RESULT_INCONSISTENT", "本轮结果不完整，无法汇总");
        }
        var book = batch.getBookId() == null ? null : wordBooks.selectOneById(batch.getBookId());
        return new BatchResultVO(batch.getId(), batch.getBookId(), book == null ? null : book.getName(),
            entries.size(), known, uncertain, unknown, uncertain + unknown, batch.getCompletedAt());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<ResultWordVO> resultWords(long batchId, ResultWordsQuery request) {
        var batch = ownedBatch(batchId, currentUser.id(), false);
        requireCompleted(batch);
        var query = QueryWrapper.create().where(column("batch_id").eq(batchId));
        if (request.getResult() != null) { query.and(column("result").eq(request.getResult())); }
        long total = details.selectCountByQuery(query);
        long offset = ((long) request.getPage() - 1) * request.getSize();
        var records = offset >= total ? List.<ResultWordVO>of()
            : details.resultWords(batchId, request.getResult(), offset, request.getSize());
        return new PageVO<>(request.getPage(), request.getSize(), total, records);
    }

    @Transactional
    public BatchVO relearn(long batchId) {
        long userId = currentUser.id();
        // Match answer's lock order: source answers cannot change during selection.
        lockUser(userId);
        var source = ownedBatch(batchId, userId, true);
        requireCompleted(source);
        if (source.getBookId() == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "来源词书不存在或无权访问");
        }
        books.requireAccessible(source.getBookId(), userId);
        var selected = entries(batchId).stream()
            .filter(item -> Objects.equals(item.getResult(), 2) || Objects.equals(item.getResult(), 3))
            .map(LearningBatchItem::getWordId).toList();
        if (selected.isEmpty()) {
            throw conflict("NO_RELEARN_WORDS", "本轮没有需要重新学习的单词");
        }
        return createBatch(userId, source.getBookId(), SelectionType.MANUAL.name(), selected);
    }

    @Transactional(readOnly = true)
    public LearningItemVO item(long batchId, long itemId) {
        var batch = ownedBatch(batchId, currentUser.id(), false);
        requireActive(batch);
        var entries = entries(batchId);
        var item = findItem(entries, itemId);
        var word = word(item.getWordId());
        boolean answered = item.getResult() != null;
        return new LearningItemVO(item.getId(), word.getId(), word.getWord(), word.getPhonetic(),
            item.getSortOrder(), item.getResult(), answered, answered ? word.getTranslation() : null,
            item.getAnsweredAt(), counts(entries).nextItemId());
    }

    @Transactional
    public AnswerVO answer(long batchId, long itemId, AnswerRequest request) {
        long userId = currentUser.id();
        // Lock order is always user -> batch. The user row also serializes answers to the
        // same word across different batches, including creation of a previously absent state.
        lockUser(userId);
        var batch = ownedBatch(batchId, userId, true);
        requireActive(batch);
        var entries = entries(batchId);
        var item = findItem(entries, itemId);
        var word = word(item.getWordId());
        boolean firstAnswer = item.getResult() == null;
        if (firstAnswer) {
            if ("COMPLETED".equals(batch.getStatus())) {
                throw conflict("BATCH_ALREADY_COMPLETED", "已完成的批次只能修正已有答案");
            }
            if (!Objects.equals(counts(entries).nextItemId(), item.getId())) {
                throw conflict("ANSWER_OUT_OF_ORDER", "请先回答当前第一条未回答明细");
            }
        }
        if (Objects.equals(item.getResult(), request.getResult())) {
            return answerView(batch, item, word, counts(entries));
        }

        // DATETIME columns have second precision. Keep the existing local-time convention.
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()).withNano(0);
        var patch = new LearningBatchItem();
        patch.setId(item.getId());
        patch.setResult(request.getResult());
        patch.setAnsweredAt(now);
        if (details.update(patch) != 1) { throw new IllegalStateException("Could not save answer"); }
        item.setResult(request.getResult());
        item.setAnsweredAt(now);
        updateWordState(userId, item, firstAnswer, now);

        var counts = counts(entries);
        if ("IN_PROGRESS".equals(batch.getStatus()) && counts.answeredCount() == counts.wordCount()) {
            var completed = new LearningBatch();
            completed.setId(batch.getId());
            completed.setStatus("COMPLETED");
            completed.setCompletedAt(now);
            completed.setUpdatedAt(now);
            if (batches.update(completed) != 1) { throw new IllegalStateException("Could not complete batch"); }
            batch.setStatus("COMPLETED");
            batch.setCompletedAt(now);
        }
        return answerView(batch, item, word, counts);
    }

    private void updateWordState(long userId, LearningBatchItem item, boolean firstAnswer, LocalDateTime now) {
        var state = states.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("word_id").eq(item.getWordId())));
        if (state == null) {
            if (!firstAnswer) { return; } // A historical correction cannot become a new latest event.
            var created = new UserWordState();
            created.setUserId(userId);
            created.setWordId(item.getWordId());
            created.setFirstLearnedAt(now);
            created.setLastLearnedAt(now);
            created.setLastResult(item.getResult());
            created.setLastBatchItemId(item.getId());
            if (states.insertSelective(created) != 1) { throw new IllegalStateException("Could not create learning state"); }
        } else if (firstAnswer || Objects.equals(state.getLastBatchItemId(), item.getId())) {
            var patch = new UserWordState();
            patch.setLastResult(item.getResult());
            patch.setLastBatchItemId(item.getId());
            patch.setLastLearnedAt(now);
            var condition = QueryWrapper.create().where(column("id").eq(state.getId()));
            if (!firstAnswer) { condition.and(column("last_batch_item_id").eq(item.getId())); }
            // first_learned_at is deliberately not part of the update.
            int updated = states.updateByQuery(patch, condition);
            if (firstAnswer && updated != 1) { throw new IllegalStateException("Could not update learning state"); }
        }
    }

    private LearningBatch ownedBatch(long batchId, long userId, boolean lock) {
        var query = QueryWrapper.create().where(column("id").eq(batchId)).and(column("user_id").eq(userId));
        if (lock) { query.forUpdate(); }
        var batch = batches.selectOneByQuery(query);
        if (batch == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND", "学习批次不存在或无权访问");
        }
        return batch;
    }

    private void lockUser(long userId) {
        if (users.selectOneByQuery(QueryWrapper.create().select(column("id"))
                .where(column("id").eq(userId)).forUpdate()) == null) {
            throw com.wordtrail.security.JwtTokenService.unauthorized();
        }
    }

    private void requireCompleted(LearningBatch batch) {
        requireActive(batch);
        if (!"COMPLETED".equals(batch.getStatus())) {
            throw conflict("BATCH_NOT_COMPLETED", "本轮学习尚未完成");
        }
    }

    private void requireActive(LearningBatch batch) {
        if ("ABANDONED".equals(batch.getStatus())) {
            throw conflict("BATCH_ABANDONED", "该学习批次已放弃");
        }
    }

    private List<LearningBatchItem> entries(long batchId) {
        // A created batch contains at most 100 items; one ordered read gives consistent
        // counts, membership checks and the first unanswered item without N+1 queries.
        return details.selectListByQuery(QueryWrapper.create().where(column("batch_id").eq(batchId))
            .orderBy(column("sort_order").asc(), column("id").asc()));
    }

    private LearningBatchItem findItem(List<LearningBatchItem> entries, long itemId) {
        return entries.stream().filter(item -> item.getId() == itemId).findFirst()
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "学习明细不存在或不属于该批次"));
    }

    private Word word(long wordId) {
        var word = words.selectOneById(wordId);
        if (word == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "WORD_NOT_FOUND", "单词不存在"); }
        return word;
    }

    private ProgressCounts counts(List<LearningBatchItem> entries) {
        long answered = entries.stream().filter(item -> item.getResult() != null).count();
        Long next = entries.stream().filter(item -> item.getResult() == null)
            .map(LearningBatchItem::getId).findFirst().orElse(null);
        return new ProgressCounts(entries.size(), answered, next);
    }

    private AnswerVO answerView(LearningBatch batch, LearningBatchItem item, Word word, ProgressCounts counts) {
        return new AnswerVO(item.getId(), item.getResult(), word.getTranslation(), item.getAnsweredAt(),
            batch.getStatus(), counts.wordCount(), counts.answeredCount(), counts.nextItemId());
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private record ProgressCounts(long wordCount, long answeredCount, Long nextItemId) {}

    @Transactional
    public BatchVO create(CreateBatchRequest request) {
        long userId = currentUser.id();
        books.requireAccessible(request.getBookId(), userId);
        List<Long> selected;
        if (request.getSelectionType() == SelectionType.BOOK) {
            if (request.getWordIds() != null && !request.getWordIds().isEmpty()) {
                throw invalid("BOOK 模式不接受非空 wordIds");
            }
            int count = request.getCount() == null ? 20 : request.getCount();
            selected = bookItems.selectUnlearned(request.getBookId(), userId, count);
            if (selected.isEmpty()) {
                throw new BusinessException(HttpStatus.CONFLICT, "NO_UNLEARNED_WORDS", "该词书没有可选的未学单词");
            }
        } else {
            if (request.getCount() != null) { throw invalid("MANUAL 模式不接受 count"); }
            if (request.getWordIds() == null || request.getWordIds().isEmpty()
                    || request.getWordIds().stream().anyMatch(id -> id == null || id <= 0)) {
                throw invalid("MANUAL 模式必须提供有效的 wordIds");
            }
            var ids = new LinkedHashSet<>(request.getWordIds());
            if (ids.size() > 100) { throw invalid("wordIds 去重后数量必须在 1～100 之间"); }
            selected = bookItems.selectRequested(request.getBookId(), new ArrayList<>(ids));
            if (selected.size() != ids.size()) {
                throw invalid("所选单词必须全部存在并属于指定词书");
            }
        }

        return createBatch(userId, request.getBookId(), request.getSelectionType().name(), selected);
    }

    // Called only inside create/relearn transactions; the original batch and states are never written here.
    private BatchVO createBatch(long userId, long bookId, String selectionType, List<Long> selected) {
        var batch = new LearningBatch();
        batch.setUserId(userId);
        batch.setBookId(bookId);
        batch.setSelectionType(selectionType);
        batch.setStatus("IN_PROGRESS");
        if (batches.insertSelective(batch) != 1 || batch.getId() == null) {
            throw new IllegalStateException("Could not persist learning batch");
        }
        var entries = new ArrayList<LearningBatchItem>(selected.size());
        for (int index = 0; index < selected.size(); index++) {
            var item = new LearningBatchItem();
            item.setBatchId(batch.getId());
            item.setWordId(selected.get(index));
            item.setSortOrder(index + 1);
            entries.add(item);
        }
        if (details.insertBatch(entries) != entries.size()) {
            throw new IllegalStateException("Could not persist all learning batch items");
        }
        return new BatchVO(batch.getId(), batch.getBookId(), batch.getSelectionType(),
            batch.getStatus(), entries.size());
    }

    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }
}
