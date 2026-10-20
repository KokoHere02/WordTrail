package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.entity.*;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.learning.vo.*;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.word.mapper.WordMapper;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;

@Service
public class DailyLearningService {
    private final CurrentUser currentUser;
    private final LearningContext context;
    private final LearningBatchMapper batches;
    private final LearningBatchItemMapper items;
    private final UserWordStateMapper states;
    private final WordMapper words;
    private final ReviewEvents events;
    private final ReviewScheduler scheduler;
    private final ObjectMapper json;
    private final LearningSettingsService settings;

    public DailyLearningService(CurrentUser currentUser, LearningContext context, LearningBatchMapper batches,
            LearningBatchItemMapper items, UserWordStateMapper states, WordMapper words, ReviewEvents events,
            ReviewScheduler scheduler, ObjectMapper json, LearningSettingsService settings) {
        this.currentUser=currentUser; this.context=context; this.batches=batches; this.items=items;
        this.states=states; this.words=words; this.events=events; this.scheduler=scheduler; this.json=json; this.settings=settings;
    }

    private QueryWrapper active(long userId) {
        return QueryWrapper.create().where(column("user_id").eq(userId)).and(column("plan_status").eq("ACTIVE"));
    }
    private QueryWrapper due(long userId, LocalDateTime now) {
        return active(userId).and(column("first_learned_at").isNotNull()).and(column("next_review_at").le(now));
    }
    private LearningBatch todayBatch(long userId, LocalDate date, boolean lock) {
        var query=QueryWrapper.create().where(column("user_id").eq(userId)).and(column("task_type").eq("DAILY"))
            .and(column("learning_date").eq(date));
        if(lock) { query.forUpdate(); }
        return batches.selectOneByQuery(query);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public TodayVO today() {
        long userId=currentUser.id(); var now=context.now(); var batch=todayBatch(userId, now.toLocalDate(), false);
        int target=batch==null ? settings.dailyTarget(userId) : batch.getDailyTarget();
        return new TodayVO(now.toLocalDate(), target, states.selectCountByQuery(active(userId)),
            states.selectCountByQuery(due(userId, now)),
            states.selectCountByQuery(active(userId).and(column("first_learned_at").isNull())),
            batch==null ? null : view(batch, context.entries(batch.getId())));
    }

    @Transactional
    public DailyTaskVO start() {
        long userId=currentUser.id(); context.lockUser(userId);
        var now=context.now(); var date=now.toLocalDate();
        var existing=todayBatch(userId, date, true);
        if(existing!=null) { return view(existing, context.entries(existing.getId())); }
        int target=settings.dailyTarget(userId);
        var selected=new ArrayList<>(states.selectListByQuery(due(userId, now)
            .orderBy(column("next_review_at").asc(), column("id").asc()).limit(target)));
        int reviews=selected.size();
        if(selected.size()<target) {
            selected.addAll(states.selectListByQuery(active(userId).and(column("first_learned_at").isNull())
                .orderBy(column("joined_at").asc(), column("id").asc()).limit(target-selected.size())));
        }
        if(selected.isEmpty()) { throw conflict("NO_DAILY_WORDS", "今日没有到期复习词或待学新词"); }
        var batch=new LearningBatch(); batch.setUserId(userId); batch.setTaskType("DAILY");
        batch.setSelectionType("DAILY"); batch.setStatus("IN_PROGRESS"); batch.setLearningDate(date);
        batch.setDailyTarget(target); batch.setCreatedAt(now); batch.setUpdatedAt(now);
        try {
            if(batches.insertSelective(batch)!=1) { throw new IllegalStateException("Could not create daily task"); }
        } catch(DuplicateKeyException ex) {
            existing=todayBatch(userId, date, true);
            if(existing==null) { throw ex; }
            return view(existing, context.entries(existing.getId()));
        }
        var entries=new ArrayList<LearningBatchItem>();
        for(int i=0;i<selected.size();i++) {
            var item=new LearningBatchItem(); item.setBatchId(batch.getId()); item.setWordId(selected.get(i).getWordId());
            item.setSortOrder(i+1); item.setQueueOrder((long)i+1); item.setItemType(i<reviews ? "REVIEW":"NEW");
            item.setLearningStatus("PENDING"); item.setAnswerCount(0); item.setRelearnCount(0); entries.add(item);
        }
        if(items.insertBatch(entries)!=entries.size()) { throw new IllegalStateException("Could not persist daily queue"); }
        return view(batch, context.entries(batch.getId()));
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public PageVO<DailyTaskVO> history(BatchQuery request) {
        long userId=currentUser.id(); var today=context.today();
        var page=batches.paginate(request.getPage(),request.getSize(),QueryWrapper.create()
            .where(column("user_id").eq(userId)).and(column("task_type").eq("DAILY"))
            .and(column("status").eq(request.getStatus().name())).orderBy(column("created_at").desc(),column("id").desc()));
        var ids=page.getRecords().stream().map(LearningBatch::getId).toList();
        var grouped=ids.isEmpty()?Map.<Long,List<LearningBatchItem>>of():items.selectListByQuery(QueryWrapper.create()
            .where(column("batch_id").in(ids))).stream().collect(java.util.stream.Collectors.groupingBy(LearningBatchItem::getBatchId));
        return new PageVO<>(request.getPage(),request.getSize(),page.getTotalRow(),page.getRecords().stream()
            .map(b->view(b,grouped.getOrDefault(b.getId(),List.of()),canContinue(b,today))).toList());
    }

    private LearningBatch daily(long userId, long batchId, boolean lock, LocalDate writingDate) {
        var batch=context.ownedBatch(userId,batchId,lock);
        if(!"DAILY".equals(batch.getTaskType())) { throw conflict("NOT_DAILY_TASK", "请使用专项练习接口"); }
        if(writingDate!=null && !writingDate.equals(batch.getLearningDate())) { throw conflict("DAILY_TASK_EXPIRED", "历史每日任务只读，请进入今日学习"); }
        if("ABANDONED".equals(batch.getStatus())) { throw conflict("BATCH_ABANDONED", "该学习批次已放弃"); }
        return batch;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public DailyTaskVO progress(long batchId) {
        var today=context.today(); var batch=daily(currentUser.id(),batchId,false,null);
        return view(batch,context.entries(batchId),canContinue(batch,today));
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public DailyItemVO item(long batchId,long itemId) {
        long userId=currentUser.id(); var today=context.today(); var batch=daily(userId,batchId,false,null);
        var entries=context.entries(batchId); var item=find(entries,itemId); var word=words.selectOneById(item.getWordId());
        var latest=events.latestAnswer(userId);
        boolean skipped="SKIPPED".equals(item.getLearningStatus());
        Long correctable=!skipped && latest!=null && latest.getBatchItemId()==itemId && today.equals(latest.getLearningDate())
            && today.equals(batch.getLearningDate()) ? latest.getId():null;
        boolean done="DONE".equals(item.getLearningStatus());
        return new DailyItemVO(itemId,word.getId(),word.getWord(),word.getPhonetic(),done?word.getTranslation():null,
            item.getItemType(),item.getLearningStatus(),item.getResult(),item.getAnswerCount(),
            done||skipped||!canContinue(batch,today)?null:item.getAnswerCount()+1,
            canContinue(batch,today)?next(entries):null,correctable);
    }

    public record ScheduleSnapshot(int reviewLevel, LocalDateTime nextReviewAt, boolean started, int itemAttemptNo) {}
    private ScheduleSnapshot snapshot(UserWordState state,int attempt) {
        return new ScheduleSnapshot(state.getReviewLevel(),state.getNextReviewAt(),state.getFirstLearnedAt()!=null,attempt);
    }
    private String encode(ScheduleSnapshot snapshot) { return json.writeValueAsString(snapshot); }
    private ScheduleSnapshot decode(String snapshot) { return json.readValue(snapshot,ScheduleSnapshot.class); }

    @Transactional
    public DailyAnswerVO answer(long batchId,long itemId,DailyAnswerRequest request) {
        long userId=currentUser.id(); context.lockUser(userId);
        var now=context.now();
        var batch=daily(userId,batchId,true,now.toLocalDate()); var entries=context.entries(batchId); var item=find(entries,itemId);
        var state=availableState(userId,item);
        var previous=events.request(userId,request.requestId(),batchId,itemId,"ANSWER",request.result(),null);
        if(previous!=null) {
            if(decode(previous.getScheduleBefore()).itemAttemptNo()!=request.attemptNo()) { throw conflict("REQUEST_ID_REUSED","requestId 已用于其他请求"); }
            return answerView(previous,batch,entries);
        }
        if(!Objects.equals(next(entries),itemId) || request.attemptNo()!=item.getAnswerCount()+1 || item.getAnswerCount()>=3) {
            throw conflict("STALE_DAILY_ANSWER", "作答位置或次数已变化，请重新查询进度");
        }
        var before=snapshot(state,request.attemptNo());
        boolean schedule=item.getAnswerCount()==0;
        if(schedule) { applySchedule(state,before,request.result(),batch.getLearningDate()); }
        if(state.getFirstLearnedAt()==null) { state.setFirstLearnedAt(now); }
        state.setLastLearnedAt(now); state.setLastResult(request.result()); state.setLastBatchItemId(itemId);
        if(states.update(state)!=1) { throw new IllegalStateException("Could not update word memory"); }
        item.setAnswerCount(item.getAnswerCount()+1); item.setRelearnCount(Math.max(0,item.getAnswerCount()-1));
        applyFeedback(item,entries,request.result(),now);
        var event=events.append(userId,item,request.requestId(),"ANSWER",null,request.result(),schedule,
            encode(before),encode(snapshot(state,request.attemptNo())),now);
        updateBatch(batch,entries,now);
        return answerView(event,batch,entries);
    }

    @Transactional
    public DailyAnswerVO correct(long batchId,long itemId,CorrectionRequest request) {
        long userId=currentUser.id(); context.lockUser(userId);
        var now=context.now();
        var batch=daily(userId,batchId,true,now.toLocalDate()); var entries=context.entries(batchId); var item=find(entries,itemId);
        var state=availableState(userId,item);
        var previous=events.request(userId,request.requestId(),batchId,itemId,"CORRECTION",request.result(),request.logId());
        if(previous!=null) { return answerView(previous,batch,entries); }
        var original=events.latestAnswer(userId);
        if(original==null || !original.getId().equals(request.logId()) || original.getBatchItemId()!=itemId
                || original.getBatchId()!=batchId || !now.toLocalDate().equals(original.getLearningDate())) {
            throw conflict("CORRECTION_NOT_ALLOWED","只能更正今日最近一次作答");
        }
        var before=snapshot(state,item.getAnswerCount());
        if(!Objects.equals(item.getResult(),request.result())) {
            if(Boolean.TRUE.equals(original.getAffectsSchedule())) {
                applySchedule(state,decode(original.getScheduleBefore()),request.result(),batch.getLearningDate());
            }
            state.setLastResult(request.result()); state.setLastLearnedAt(now); state.setLastBatchItemId(itemId);
            if(states.update(state)!=1) { throw new IllegalStateException("Could not correct word memory"); }
            applyFeedback(item,entries,request.result(),now);
        }
        var event=events.append(userId,item,request.requestId(),"CORRECTION",original.getId(),request.result(),
            original.getAffectsSchedule(),encode(before),encode(snapshot(state,item.getAnswerCount())),now);
        updateBatch(batch,entries,now);
        return answerView(event,batch,entries);
    }

    private UserWordState availableState(long userId,LearningBatchItem item) {
        if ("SKIPPED".equals(item.getLearningStatus())) {
            throw conflict("PLAN_WORD_UNAVAILABLE","该词已暂停、移出或本日已跳过，请重新获取任务进度和下一词");
        }
        var state=states.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId)).and(column("word_id").eq(item.getWordId())));
        if(state==null) { throw conflict("PLAN_WORD_NOT_FOUND","学习计划记录不存在"); }
        if (!"ACTIVE".equals(state.getPlanStatus())) {
            throw conflict("PLAN_WORD_UNAVAILABLE","该词已暂停或移出计划，请重新获取任务进度和下一词");
        }
        return state;
    }
    private void applySchedule(UserWordState state,ScheduleSnapshot before,int result,LocalDate date) {
        var schedule=scheduler.schedule(before.reviewLevel(),before.started(),result,date);
        state.setReviewLevel(schedule.reviewLevel()); state.setNextReviewAt(schedule.nextReviewAt());
    }
    private void applyFeedback(LearningBatchItem item,List<LearningBatchItem> entries,int result,LocalDateTime now) {
        item.setResult(result); item.setAnsweredAt(now);
        boolean done=result==1 || item.getAnswerCount()>=3;
        item.setLearningStatus(done?"DONE":"RELEARNING"); item.setCompletedAt(done?now:null);
        if(!done) {
            var others=entries.stream().filter(i->!i.getId().equals(item.getId()) && queued(i))
                .sorted(queueOrder()).toList();
            long position=others.isEmpty()?item.getQueueOrder():others.get(Math.min(3,others.size())-1).getQueueOrder();
            item.setQueueOrder(position+1);
        }
        // Explicit NULL is required when a correction reopens a completed item.
        if(items.updateByQuery(item,false,QueryWrapper.create().where(column("id").eq(item.getId())))!=1) {
            throw new IllegalStateException("Could not persist daily feedback");
        }
    }
    private void updateBatch(LearningBatch batch,List<LearningBatchItem> entries,LocalDateTime now) {
        boolean done=next(entries)==null;
        String status=done?"COMPLETED":"IN_PROGRESS";
        if(!status.equals(batch.getStatus())) {
            batch.setStatus(status); batch.setCompletedAt(done?now:null);
        }
        batch.setUpdatedAt(now);
        if(batches.updateByQuery(batch,false,QueryWrapper.create().where(column("id").eq(batch.getId())))!=1) {
            throw new IllegalStateException("Could not save daily progress");
        }
    }
    private LearningBatchItem find(List<LearningBatchItem> entries,long id) {
        return entries.stream().filter(i->i.getId()==id).findFirst().orElseThrow(()->
            new BusinessException(HttpStatus.NOT_FOUND,"ITEM_NOT_FOUND","学习明细不存在或不属于该批次"));
    }
    private Comparator<LearningBatchItem> queueOrder() {
        return Comparator.comparing(LearningBatchItem::getQueueOrder).thenComparing(LearningBatchItem::getSortOrder)
            .thenComparing(LearningBatchItem::getId);
    }
    private Long next(List<LearningBatchItem> entries) {
        return entries.stream().filter(this::queued).min(queueOrder())
            .map(LearningBatchItem::getId).orElse(null);
    }
    private boolean queued(LearningBatchItem item) {
        return "PENDING".equals(item.getLearningStatus()) || "RELEARNING".equals(item.getLearningStatus());
    }
    private DailyTaskVO view(LearningBatch batch,List<LearningBatchItem> entries) {
        return view(batch,entries,true);
    }
    private boolean canContinue(LearningBatch batch,LocalDate today) {
        return today.equals(batch.getLearningDate()) && "IN_PROGRESS".equals(batch.getStatus());
    }
    private DailyTaskVO view(LearningBatch batch,List<LearningBatchItem> entries,boolean actionable) {
        return new DailyTaskVO(batch.getId(),batch.getLearningDate(),batch.getDailyTarget(),batch.getTaskType(),batch.getStatus(),entries.size(),
            entries.stream().filter(i->"DONE".equals(i.getLearningStatus())).count(),
            entries.stream().filter(i->"SKIPPED".equals(i.getLearningStatus())).count(),
            entries.stream().mapToLong(LearningBatchItem::getAnswerCount).sum(),
            entries.stream().filter(i->"DONE".equals(i.getLearningStatus()) && !Objects.equals(i.getResult(),1)).count(),
            entries.stream().filter(i->"NEW".equals(i.getItemType())).count(),
            entries.stream().filter(i->"REVIEW".equals(i.getItemType())).count(),actionable?next(entries):null);
    }
    private DailyAnswerVO answerView(WordReviewLog event,LearningBatch batch,List<LearningBatchItem> entries) {
        var word=words.selectOneById(event.getWordId());
        return new DailyAnswerVO(event.getId(),event.getBatchItemId(),event.getResult(),word.getTranslation(),
            event.getAnsweredAt(),"CORRECTION".equals(event.getEventType()),view(batch,entries));
    }
}
