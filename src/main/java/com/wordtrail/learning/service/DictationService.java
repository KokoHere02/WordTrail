package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.book.service.BookService;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.entity.*;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.learning.vo.*;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.word.support.TranslationParser;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;

@Service @RequiredArgsConstructor
public class DictationService {
    private final CurrentUser currentUser;
    private final LearningContext context;
    private final BookService books;
    private final LearningBatchMapper batches;
    private final LearningBatchItemMapper items;
    private final DictationMapper logs;

    @Transactional
    public DictationTaskVO create(CreateDictationRequest request) {
        long userId=currentUser.id(); context.lockUser(userId);
        if(request.getBookId()!=null) books.requireAccessible(request.getBookId(),userId);
        List<Long> selected=request.getWordIds()==null?null:new ArrayList<>(new LinkedHashSet<>(request.getWordIds()));
        if(selected!=null && (selected.isEmpty() || selected.size()>100 || request.getCount()!=null))
            throw conflict("INVALID_DICTATION_SELECTION","手选须提供 1～100 个不同单词且不能同时传 count");
        var candidates=logs.candidates(userId,request.getSource().name(),request.getBookId(),selected,
            selected==null?(request.getCount()==null?20:request.getCount()):selected.size());
        if(selected!=null) {
            if(candidates.size()!=selected.size()) throw conflict("DICTATION_WORD_UNAVAILABLE","所选词须全部满足来源、词本和释义条件，请刷新选词");
            var indexed=candidates.stream().collect(Collectors.toMap(DictationMapper.Candidate::wordId,Function.identity()));
            candidates=selected.stream().map(indexed::get).toList();
        }
        if(candidates.isEmpty()) throw conflict("NO_DICTATION_WORDS","没有可用于默写的单词；计划词须 ACTIVE、已开始且有释义");
        var now=context.now(); var batch=new LearningBatch();
        batch.setUserId(userId); batch.setBookId(request.getBookId()); batch.setTaskType("PRACTICE");
        batch.setPracticeMode("DICTATION"); batch.setSelectionType("MANUAL"); batch.setStatus("IN_PROGRESS");
        batch.setCreatedAt(now); batch.setUpdatedAt(now);
        if(batches.insertSelective(batch)!=1) throw new IllegalStateException("Could not create dictation batch");
        var entries=new ArrayList<LearningBatchItem>();
        for(var candidate:candidates) {
            var item=new LearningBatchItem(); item.setBatchId(batch.getId()); item.setWordId(candidate.wordId());
            item.setSortOrder(entries.size()+1); item.setQueueOrder((long)entries.size()+1);
            item.setLearningStatus("PENDING"); item.setAnswerCount(0); item.setRelearnCount(0);
            item.setDictationTarget(candidate.word().strip()); item.setDictationTranslation(candidate.translation());
            item.setDictationHadFailure(request.getSource()==CreateDictationRequest.Source.MISTAKES);
            item.setDictationCorrectStreak(0); entries.add(item);
        }
        if(items.insertBatch(entries)!=entries.size()) throw new IllegalStateException("Could not create all dictation items");
        return progress(batch,context.entries(batch.getId()));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DictationTaskVO progress(long batchId) { var batch=owned(batchId,false); return progress(batch,context.entries(batchId)); }

    // Explicit presentation step, persisted and stable across refresh; no learning event is created.
    @Transactional
    public DictationQuestionVO next(long batchId) {
        context.lockUser(currentUser.id()); var batch=owned(batchId,true);
        if(!"IN_PROGRESS".equals(batch.getStatus())) throw conflict("DICTATION_NOT_IN_PROGRESS","本轮默写已结束，请查看结果");
        var item=next(context.entries(batchId));
        if(item==null) throw conflict("DICTATION_NOT_IN_PROGRESS","本轮没有待答题目");
        if(item.getDictationPresentationId()==null) {
            item.setDictationPresentationId(UUID.randomUUID().toString()); save(item);
        }
        String prompt=DictationPolicy.prompt(item.getDictationTranslation(),item.getDictationTarget());
        return new DictationQuestionVO(item.getId(),prompt,TranslationParser.parse(prompt),item.getDictationPresentationId(),
            item.getAnswerCount()+1,item.getDictationCorrectStreak(),item.getDictationHadFailure()?2:1,DictationPolicy.MAX_ATTEMPTS);
    }

    @Transactional
    public DictationAnswerVO answer(long batchId,long itemId,DictationAnswerRequest request) {
        long userId=currentUser.id(); context.lockUser(userId); var batch=owned(batchId,true);
        var entries=context.entries(batchId);
        var item=entries.stream().filter(value->value.getId()==itemId).findFirst()
            .orElseThrow(()->conflict("DICTATION_ITEM_NOT_FOUND","题目不属于本轮默写"));
        boolean reveal=request.action()==DictationAnswerRequest.Action.REVEAL;
        if(reveal?request.input()!=null:request.input()==null || request.input().isBlank())
            throw conflict("INVALID_DICTATION_ANSWER","ANSWER 须有输入，REVEAL 不接受 input");
        var previous=logs.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId)).and(column("request_id").eq(request.requestId())));
        if(previous!=null) {
            if(previous.getBatchId()!=batchId || previous.getBatchItemId()!=itemId || !previous.getEventType().equals(request.action().name())
                || !Objects.equals(previous.getInputText(),request.input()) || !previous.getPresentationId().equals(request.presentationId())
                || !previous.getAttemptNo().equals(request.attemptNo())) throw conflict("REQUEST_ID_REUSED","请求标识已用于其他内容");
            return answerView(previous,item,progress(batch,entries));
        }
        if(!"IN_PROGRESS".equals(batch.getStatus()) || next(entries)==null || next(entries).getId()!=itemId
            || !Objects.equals(item.getDictationPresentationId(),request.presentationId()) || item.getAnswerCount()+1!=request.attemptNo())
            throw conflict("STALE_DICTATION_ANSWER","题目或作答序号已变化，请重新获取下一题");
        int before=item.getDictationCorrectStreak(); var now=context.now();
        var outcome=DictationPolicy.answer(item.getDictationTarget(),request.input(),reveal,item.getDictationHadFailure(),before,request.attemptNo());
        item.setAnswerCount(request.attemptNo()); item.setAnsweredAt(now); item.setDictationHadFailure(outcome.hadFailure());
        item.setDictationCorrectStreak(outcome.correctStreak()); item.setDictationPresentationId(null);
        item.setLearningStatus(outcome.done()?"DONE":"RELEARNING"); item.setCompletionReason(outcome.completionReason());
        item.setCompletedAt(outcome.done()?now:null); item.setRelearnCount(Math.max(item.getAnswerCount()-1,0));
        save(item);
        if(!outcome.done()) requeue(entries,item);
        var event=new DictationReviewLog(); event.setUserId(userId); event.setWordId(item.getWordId());
        event.setBatchId(batchId); event.setBatchItemId(itemId); event.setRequestId(request.requestId()); event.setPresentationId(request.presentationId());
        event.setEventType(request.action().name()); event.setInputText(request.input()); event.setVerdict(outcome.verdict());
        event.setAttemptNo(request.attemptNo()); event.setLearningDate(now.toLocalDate()); event.setAnsweredAt(now);
        event.setCorrectStreakBefore(before); event.setCorrectStreakAfter(outcome.correctStreak()); event.setHadFailureAfter(outcome.hadFailure());
        event.setItemStatusAfter(item.getLearningStatus()); event.setCompletionReasonAfter(outcome.completionReason()); event.setAffectsSchedule(false);
        if(logs.insertSelective(event)!=1) throw new IllegalStateException("Could not persist dictation event");
        if(next(entries)==null) { batch.setStatus("COMPLETED"); batch.setCompletedAt(now); }
        batch.setUpdatedAt(now); if(batches.update(batch)!=1) throw new IllegalStateException("Could not persist dictation progress");
        return answerView(event,item,progress(batch,entries));
    }

    private void requeue(List<LearningBatchItem> entries,LearningBatchItem current) {
        var queue=entries.stream().filter(item->!"DONE".equals(item.getLearningStatus()) && !item.getId().equals(current.getId()))
            .sorted(Comparator.comparing(LearningBatchItem::getQueueOrder).thenComparing(LearningBatchItem::getId))
            .collect(Collectors.toCollection(ArrayList::new));
        queue.add(Math.min(3,queue.size()),current);
        for(int index=0;index<queue.size();index++) {
            var item=queue.get(index); item.setQueueOrder((long)index+1); save(item);
        }
    }
    private void save(LearningBatchItem item) {
        if(items.updateByQuery(item,false,QueryWrapper.create().where(column("id").eq(item.getId())))!=1)
            throw new IllegalStateException("Could not persist dictation item");
    }
    private LearningBatchItem next(List<LearningBatchItem> entries) {
        return entries.stream().filter(item->!"DONE".equals(item.getLearningStatus()))
            .min(Comparator.comparing(LearningBatchItem::getQueueOrder).thenComparing(LearningBatchItem::getId)).orElse(null);
    }
    private LearningBatch owned(long batchId,boolean lock) {
        var batch=context.ownedBatch(currentUser.id(),batchId,lock);
        if(!"DICTATION".equals(batch.getPracticeMode())) throw conflict("NOT_DICTATION_TASK","该批次不是默写，请使用原学习接口");
        return batch;
    }
    private DictationTaskVO progress(LearningBatch batch,List<LearningBatchItem> entries) {
        long completed=entries.stream().filter(item->"DONE".equals(item.getLearningStatus())).count();
        long passed=entries.stream().filter(item->"RECALLED".equals(item.getCompletionReason())).count();
        var next=next(entries);
        return new DictationTaskVO(batch.getId(),batch.getBookId(),batch.getTaskType(),batch.getPracticeMode(),batch.getStatus(),
            entries.size(),completed,passed,completed-passed,entries.stream().mapToLong(LearningBatchItem::getAnswerCount).sum(),
            "IN_PROGRESS".equals(batch.getStatus()) && next!=null?next.getId():null,DictationPolicy.MAX_ATTEMPTS,batch.getCreatedAt(),batch.getCompletedAt());
    }
    private DictationAnswerVO answerView(DictationReviewLog event,LearningBatchItem item,DictationTaskVO progress) {
        return new DictationAnswerVO(event.getId(),item.getId(),event.getEventType(),event.getInputText(),item.getDictationTarget(),
            item.getDictationTranslation(),event.getVerdict(),event.getAttemptNo(),event.getCorrectStreakAfter(),
            event.getItemStatusAfter(),event.getCompletionReasonAfter(),event.getAnsweredAt(),false,progress);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<DictationTaskVO> list(BatchQuery query) {
        var page=batches.paginate(query.getPage(),query.getSize(),QueryWrapper.create().where(column("user_id").eq(currentUser.id()))
            .and(column("practice_mode").eq("DICTATION")).and(column("status").eq(query.getStatus().name()))
            .orderBy(column("created_at").desc(),column("id").desc()));
        var ids=page.getRecords().stream().map(LearningBatch::getId).toList();
        var grouped=ids.isEmpty()?Map.<Long,List<LearningBatchItem>>of():items.selectListByQuery(QueryWrapper.create().where(column("batch_id").in(ids))).stream()
            .collect(Collectors.groupingBy(LearningBatchItem::getBatchId));
        return new PageVO<>(query.getPage(),query.getSize(),page.getTotalRow(),page.getRecords().stream().map(batch->progress(batch,grouped.get(batch.getId()))).toList());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<DictationWordVO> result(long batchId,WordHistoryQuery query) {
        var batch=owned(batchId,false);
        if(!"COMPLETED".equals(batch.getStatus())) throw conflict("DICTATION_NOT_COMPLETED","本轮尚未结束，请继续获取下一题");
        long count=items.selectCountByQuery(QueryWrapper.create().where(column("batch_id").eq(batchId)));
        long offset=((long)query.getPage()-1)*query.getSize();
        return new PageVO<>(query.getPage(),query.getSize(),count,offset>=count?List.of():logs.resultWords(batchId,offset,query.getSize()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<DictationWordVO> mistakes(WordHistoryQuery query) {
        long userId=currentUser.id(),count=logs.countMistakes(userId),offset=((long)query.getPage()-1)*query.getSize();
        return new PageVO<>(query.getPage(),query.getSize(),count,offset>=count?List.of():logs.mistakes(userId,offset,query.getSize()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<DictationHistoryVO> history(long wordId,WordHistoryQuery query) {
        long userId=currentUser.id(),count=logs.countHistory(userId,wordId),offset=((long)query.getPage()-1)*query.getSize();
        return new PageVO<>(query.getPage(),query.getSize(),count,offset>=count?List.of():logs.history(userId,wordId,offset,query.getSize()));
    }
}
