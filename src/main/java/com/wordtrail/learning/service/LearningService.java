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
    private final WordMapper words;
    private final LearningContext context;
    private final ReviewEvents events;
    private final ReviewEvidenceMapper evidence;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<BatchListVO> list(BatchQuery request) {
        long userId = currentUser.id();
        var query = QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("status").eq(request.getStatus().name()))
            .and(column("task_type").eq("PRACTICE"))
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
        var coverage = evidence.coverage(currentUser.id(), "PRACTICE", batchId);
        return new BatchResultVO(batch.getId(), batch.getBookId(), book == null ? null : book.getName(),
            entries.size(), known, uncertain, unknown, uncertain + unknown, batch.getCompletedAt(),
            known + uncertain + unknown, coverage.answerCount(), uncertain + unknown,
            coverage.incompleteWordCount()==0, coverage.incompleteWordCount(), "RECORDED_EVENTS_AND_ITEMS");
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
            : evidence.resultWords(currentUser.id(), "PRACTICE", batchId, request.getResult(), offset, request.getSize());
        return new PageVO<>(request.getPage(), request.getSize(), total, records);
    }

    @Transactional
    public BatchVO relearn(long batchId) {
        long userId = currentUser.id();
        // Match answer's lock order: source answers cannot change during selection.
        lockUser(userId);
        var source = ownedBatch(batchId, userId, true);
        requireCompleted(source);
        if (source.getBookId() != null) { books.requireAccessible(source.getBookId(), userId); }
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
        // Lock order is always user -> batch, shared with daily answers and plan changes.
        lockUser(userId);
        var now = context.now();
        var batch = ownedBatch(batchId, userId, true);
        requireActive(batch);
        var entries = entries(batchId);
        var item = findItem(entries, itemId);
        var word = word(item.getWordId());
        var previous = events.findRequest(userId, request.getRequestId());
        if (previous != null) {
            if (previous.getBatchId() != batchId || previous.getBatchItemId() != itemId
                    || !Objects.equals(previous.getResult(), request.getResult())) {
                throw conflict("REQUEST_ID_REUSED", "requestId 已用于其他请求");
            }
            var counts = counts(entries);
            return new AnswerVO(itemId, previous.getResult(), word.getTranslation(), previous.getAnsweredAt(),
                batch.getStatus(), counts.wordCount(), counts.answeredCount(), counts.nextItemId());
        }
        boolean firstAnswer = item.getResult() == null;
        if (firstAnswer) {
            if ("COMPLETED".equals(batch.getStatus())) {
                throw conflict("BATCH_ALREADY_COMPLETED", "已完成的批次只能修正已有答案");
            }
            if (!Objects.equals(counts(entries).nextItemId(), item.getId())) {
                throw conflict("ANSWER_OUT_OF_ORDER", "请先回答当前第一条未回答明细");
            }
        }
        if (Objects.equals(item.getResult(), request.getResult()) && request.getRequestId() == null) {
            return answerView(batch, item, word, counts(entries));
        }
        var original = events.latestAnswer(userId);
        if (!firstAnswer && (original == null || original.getBatchItemId() != itemId
                || !now.toLocalDate().equals(original.getLearningDate()))) {
            throw conflict("CORRECTION_NOT_ALLOWED", "只能更正今日最近一次作答，历史练习结果只读");
        }
        if (Objects.equals(item.getResult(), request.getResult())) {
            // Reserve an explicit idempotency key even for a no-op, without changing learning time.
            events.append(userId, item, request.getRequestId(), "CORRECTION", original.getId(),
                request.getResult(), false, null, null, item.getAnsweredAt());
            return answerView(batch, item, word, counts(entries));
        }

        var patch = new LearningBatchItem();
        patch.setId(item.getId());
        patch.setResult(request.getResult());
        patch.setAnsweredAt(now);
        patch.setLearningStatus("DONE"); patch.setCompletedAt(now);
        patch.setAnswerCount(firstAnswer ? item.getAnswerCount() + 1 : item.getAnswerCount());
        if (details.update(patch) != 1) { throw new IllegalStateException("Could not save answer"); }
        item.setResult(request.getResult());
        item.setAnsweredAt(now);
        item.setLearningStatus("DONE"); item.setAnswerCount(patch.getAnswerCount()); item.setCompletedAt(now);
        // Practice is an isolated exercise: it never creates, joins or schedules a plan word.
        events.append(userId, item, request.getRequestId() == null ? UUID.randomUUID().toString() : request.getRequestId(),
            firstAnswer ? "ANSWER" : "CORRECTION", firstAnswer ? null : original.getId(), request.getResult(), false, null, null, now);

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

    private LearningBatch ownedBatch(long batchId, long userId, boolean lock) {
        var batch = context.ownedBatch(userId, batchId, lock);
        if ("DAILY".equals(batch.getTaskType())) {
            throw conflict("DAILY_ENDPOINT_REQUIRED", "每日任务请使用 /daily 接口，完成状态不能通过 result 判定");
        }
        return batch;
    }

    private void lockUser(long userId) {
        context.lockUser(userId);
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
        return context.entries(batchId);
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
        lockUser(userId);
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

    @Transactional
    public BatchVO createWeakPractice(WeakPracticeRequest request) {
        long userId = currentUser.id(); lockUser(userId);
        var selected = new ArrayList<>(new LinkedHashSet<>(request.wordIds()));
        if (selected.isEmpty() || selected.size()>100) { throw invalid("每次只能选择 1～100 个不同单词"); }
        if (words.selectCountByQuery(QueryWrapper.create().where(column("id").in(selected))) != selected.size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "WORD_NOT_FOUND", "部分单词不存在，未创建专项练习");
        }
        if (evidence.weakSelection(userId, request.source().name(), null, selected).size()!=selected.size()) {
            throw conflict("WEAK_WORD_SELECTION_CHANGED", "所选单词不再全部属于该来源的薄弱词，请刷新列表后重试");
        }
        return createBatch(userId, null, SelectionType.MANUAL.name(), selected);
    }

    // Source events establish ownership for cross-book practice; no plan or source item is changed.
    private BatchVO createBatch(long userId, Long bookId, String selectionType, List<Long> selected) {
        var batch = new LearningBatch();
        batch.setUserId(userId);
        batch.setBookId(bookId);
        batch.setSelectionType(selectionType);
        batch.setTaskType("PRACTICE");
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
            item.setQueueOrder((long) index + 1); item.setLearningStatus("PENDING");
            item.setAnswerCount(0); item.setRelearnCount(0);
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
