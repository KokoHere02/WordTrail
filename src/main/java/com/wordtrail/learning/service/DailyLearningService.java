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
    private final DailyReinforcementPolicy reinforcement;

    public DailyLearningService(CurrentUser currentUser, LearningContext context, LearningBatchMapper batches,
            LearningBatchItemMapper items, UserWordStateMapper states, WordMapper words, ReviewEvents events,
            ReviewScheduler scheduler, ObjectMapper json, LearningSettingsService settings, DailyReinforcementPolicy reinforcement) {
        this.currentUser=currentUser; this.context=context; this.batches=batches; this.items=items;
        this.states=states; this.words=words; this.events=events; this.scheduler=scheduler; this.json=json; this.settings=settings;
        this.reinforcement=reinforcement;
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
        var entries=batch==null ? List.<LearningBatchItem>of() : context.entries(batch.getId());
        long unassigned=states.selectCountByQuery(excluding(due(userId,now),entries))
            +states.selectCountByQuery(excluding(active(userId).and(column("first_learned_at").isNull()),entries));
        return new TodayVO(now.toLocalDate(), target, states.selectCountByQuery(active(userId)),
            states.selectCountByQuery(due(userId, now)),
            states.selectCountByQuery(active(userId).and(column("first_learned_at").isNull())),
            unassigned,batch!=null && "COMPLETED".equals(batch.getStatus()) && unassigned>0,
            batch==null ? null : view(batch, entries));
    }

    @Transactional
    public DailyTaskVO start() {
        long userId=currentUser.id(); context.lockUser(userId);
        var now=context.now(); var date=now.toLocalDate();
        var existing=todayBatch(userId, date, true);
        if(existing!=null) {
            var entries=context.entries(existing.getId());
            if(!"COMPLETED".equals(existing.getStatus())) { return view(existing,entries); }
            var selection=selectWords(userId,now,existing.getDailyTarget(),entries);
            if(selection.words().isEmpty()) { return view(existing,entries); }
            appendItems(existing,entries,selection);
            updateBatch(existing,entries,now);
            return view(existing,entries);
        }
        int target=settings.dailyTarget(userId);
        var selection=selectWords(userId,now,target,List.of());
        if(selection.words().isEmpty()) { throw conflict("NO_DAILY_WORDS", "今日没有到期复习词或待学新词"); }
        var batch=new LearningBatch(); batch.setUserId(userId); batch.setTaskType("DAILY");
        batch.setSelectionType("DAILY"); batch.setStatus("IN_PROGRESS"); batch.setLearningDate(date);
        batch.setDailyTarget(target); batch.setCreatedAt(now); batch.setUpdatedAt(now);
        batch.setStudyMode(settings.preferredMode(userId)); batch.setModeVersion(0L);
        try {
            if(batches.insertSelective(batch)!=1) { throw new IllegalStateException("Could not create daily task"); }
        } catch(DuplicateKeyException ex) {
            existing=todayBatch(userId, date, true);
            if(existing==null) { throw ex; }
            return view(existing, context.entries(existing.getId()));
        }
        var entries=new ArrayList<LearningBatchItem>();
        appendItems(batch,entries,selection);
        return view(batch, context.entries(batch.getId()));
    }

    private QueryWrapper excluding(QueryWrapper query,List<LearningBatchItem> entries) {
        if(!entries.isEmpty()) { query.and(column("word_id").notIn(entries.stream().map(LearningBatchItem::getWordId).toList())); }
        return query;
    }
    private record Selection(List<UserWordState> words,int reviews) {}
    private Selection selectWords(long userId,LocalDateTime now,int limit,List<LearningBatchItem> entries) {
        var selected=new ArrayList<>(states.selectListByQuery(excluding(due(userId,now),entries)
            .orderBy(column("next_review_at").asc(),column("id").asc()).limit(limit)));
        int reviews=selected.size();
        if(reviews<limit) {
            selected.addAll(states.selectListByQuery(excluding(active(userId).and(column("first_learned_at").isNull()),entries)
                .orderBy(column("joined_at").asc(),column("id").asc()).limit(limit-reviews)));
        }
        return new Selection(selected,reviews);
    }
    private void appendItems(LearningBatch batch,List<LearningBatchItem> entries,Selection selection) {
        int sort=entries.stream().mapToInt(LearningBatchItem::getSortOrder).max().orElse(0);
        long queue=entries.stream().mapToLong(LearningBatchItem::getQueueOrder).max().orElse(0);
        var added=new ArrayList<LearningBatchItem>();
        var wordSnapshots=words.selectListByQuery(QueryWrapper.create().where(column("id").in(
            selection.words().stream().map(UserWordState::getWordId).toList()))).stream()
            .collect(java.util.stream.Collectors.toMap(com.wordtrail.word.entity.Word::getId,java.util.function.Function.identity()));
        for(int i=0;i<selection.words().size();i++) {
            var item=new LearningBatchItem(); item.setBatchId(batch.getId()); item.setWordId(selection.words().get(i).getWordId());
            item.setSortOrder(sort+i+1); item.setQueueOrder(queue+i+1); item.setItemType(i<selection.reviews()?"REVIEW":"NEW");
            item.setLearningStatus("PENDING"); item.setAnswerCount(0); item.setRelearnCount(0); item.setReinforcementRemaining(0);
            var word=wordSnapshots.get(item.getWordId());
            item.setStudyWord(word.getWord()); item.setStudyTranslation(word.getTranslation()); item.setStudyPhonetic(word.getPhonetic());
            added.add(item);
        }
        if(items.insertBatch(added)!=added.size()) { throw new IllegalStateException("Could not persist daily queue"); }
        entries.addAll(context.entries(batch.getId()).stream().filter(i->i.getSortOrder()>sort).toList());
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

    LearningBatch daily(long userId, long batchId, boolean lock, LocalDate writingDate) {
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
        if(!"RECOGNITION".equals(batch.getStudyMode())) throw conflict("TRAINING_ENDPOINT_REQUIRED", "当前模式请使用统一训练接口");
        var entries=context.entries(batchId); var item=find(entries,itemId); var word=words.selectOneById(item.getWordId());
        var latest=events.latestAnswer(userId);
        boolean skipped="SKIPPED".equals(item.getLearningStatus());
        Long correctable=!skipped && latest!=null && latest.getBatchItemId()==itemId && today.equals(latest.getLearningDate())
            && today.equals(batch.getLearningDate()) && "RECOGNITION".equals(latest.getFeedbackMode()) ? latest.getId():null;
        boolean done="DONE".equals(item.getLearningStatus());
        return new DailyItemVO(itemId,word.getId(),word.getWord(),word.getPhonetic(),done?word.getTranslation():null,
            item.getItemType(),item.getLearningStatus(),item.getResult(),item.getAnswerCount(),
            done||skipped||!canContinue(batch,today)?null:item.getAnswerCount()+1,
            canContinue(batch,today)?next(entries):null,correctable,
            item.getReinforcementRemaining(),DailyReinforcementPolicy.MAX_ANSWERS,item.getCompletionReason());
    }

    public record ScheduleSnapshot(int reviewLevel, LocalDateTime nextReviewAt, boolean started, int itemAttemptNo,
        Integer reinforcementRemaining) {}
    private ScheduleSnapshot snapshot(UserWordState state,int attempt,int remaining) {
        return new ScheduleSnapshot(state.getReviewLevel(),state.getNextReviewAt(),state.getFirstLearnedAt()!=null,attempt,remaining);
    }
    private String encode(ScheduleSnapshot snapshot) { return json.writeValueAsString(snapshot); }
    private ScheduleSnapshot decode(String snapshot) { return json.readValue(snapshot,ScheduleSnapshot.class); }

    @Transactional
    public DailyAnswerVO answer(long batchId,long itemId,DailyAnswerRequest request) {
        return answerInMode(batchId,itemId,request,StudyMode.RECOGNITION,false,false,null);
    }

    DailyAnswerVO answerInMode(long batchId,long itemId,DailyAnswerRequest request,StudyMode mode,boolean hinted,boolean unified,
            LocalDateTime actionTime) {
        long userId=currentUser.id(); context.lockUser(userId);
        var now=actionTime==null?context.now():actionTime;
        var batch=daily(userId,batchId,true,now.toLocalDate()); var entries=context.entries(batchId); var item=find(entries,itemId);
        if(!unified && (!"RECOGNITION".equals(batch.getStudyMode()) || item.getStudyPresentationId()!=null))
            throw conflict("TRAINING_ENDPOINT_REQUIRED", "请按当前展示使用统一训练接口");
        var state=availableState(userId,item);
        var previous=events.request(userId,request.requestId(),batchId,itemId,"ANSWER",request.result(),null);
        if(previous!=null) {
            if(decode(previous.getScheduleBefore()).itemAttemptNo()!=request.attemptNo()) { throw conflict("REQUEST_ID_REUSED","requestId 已用于其他请求"); }
            return answerView(previous,batch,entries);
        }
        if(!Objects.equals(next(entries),itemId) || request.attemptNo()!=item.getAnswerCount()+1 || item.getAnswerCount()>=DailyReinforcementPolicy.MAX_ANSWERS) {
            throw conflict("STALE_DAILY_ANSWER", "作答位置或次数已变化，请重新查询进度");
        }
        int remaining=remaining(userId,item);
        var before=snapshot(state,request.attemptNo(),remaining);
        boolean schedule=item.getAnswerCount()==0;
        if(schedule) {
            int schedulingResult=mode==StudyMode.FOLLOW_TYPING || (mode==StudyMode.DICTATION && hinted) ? 2 : request.result();
            applySchedule(state,before,schedulingResult,batch.getLearningDate());
        } else if(mode!=StudyMode.RECOGNITION && (mode==StudyMode.FOLLOW_TYPING || hinted || request.result()!=1)
                && state.getNextReviewAt()!=null && state.getNextReviewAt().isAfter(batch.getLearningDate().plusDays(1).atStartOfDay())) {
            applySchedule(state,before,2,batch.getLearningDate()); schedule=true;
        }
        if(state.getFirstLearnedAt()==null) { state.setFirstLearnedAt(now); }
        state.setLastLearnedAt(now); state.setLastResult(request.result()); state.setLastBatchItemId(itemId);
        if(states.update(state)!=1) { throw new IllegalStateException("Could not update word memory"); }
        item.setAnswerCount(item.getAnswerCount()+1); item.setRelearnCount(Math.max(0,item.getAnswerCount()-1));
        item.setStudyHintUsed(true);
        applyFeedback(item,entries,request.result(),now,remaining,mode,hinted);
        var event=events.append(userId,item,request.requestId(),"ANSWER",null,request.result(),schedule,
            encode(before),encode(snapshot(state,request.attemptNo(),item.getReinforcementRemaining())),now);
        if(mode!=StudyMode.RECOGNITION) events.markMode(event,mode.name());
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
                || original.getBatchId()!=batchId || !now.toLocalDate().equals(original.getLearningDate())
                || !"RECOGNITION".equals(original.getFeedbackMode())) {
            throw conflict("CORRECTION_NOT_ALLOWED","只能更正今日最近一次作答");
        }
        var before=snapshot(state,item.getAnswerCount(),remaining(userId,item));
        if(!Objects.equals(item.getResult(),request.result())) {
            var originalBefore=decode(original.getScheduleBefore());
            if(Boolean.TRUE.equals(original.getAffectsSchedule())) {
                applySchedule(state,decode(original.getScheduleBefore()),request.result(),batch.getLearningDate());
            }
            state.setLastResult(request.result()); state.setLastLearnedAt(now); state.setLastBatchItemId(itemId);
            if(states.update(state)!=1) { throw new IllegalStateException("Could not correct word memory"); }
            int originalRemaining=originalBefore.reinforcementRemaining()!=null ? originalBefore.reinforcementRemaining()
                : replayRemaining(userId,item,true);
            applyFeedback(item,entries,request.result(),now,originalRemaining);
        }
        var event=events.append(userId,item,request.requestId(),"CORRECTION",original.getId(),request.result(),
            original.getAffectsSchedule(),encode(before),encode(snapshot(state,item.getAnswerCount(),remaining(userId,item))),now);
        updateBatch(batch,entries,now);
        return answerView(event,batch,entries);
    }

    UserWordState availableState(long userId,LearningBatchItem item) {
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
    private int remaining(long userId,LearningBatchItem item) {
        return item.getReinforcementRemaining()!=null ? item.getReinforcementRemaining() : replayRemaining(userId,item,false);
    }
    private int replayRemaining(long userId,LearningBatchItem item,boolean beforeLast) {
        var answers=events.effectiveItemAnswers(userId,item.getId());
        if(answers.size()!=item.getAnswerCount()) { throw conflict("REVIEW_HISTORY_INCOMPLETE","该词的学习事件不完整，无法安全继续或更正，请刷新并核查历史记录"); }
        int remaining=0;
        for(int i=0;i<answers.size()-(beforeLast?1:0);i++) {
            remaining=reinforcement.feedback(remaining,i+1,answers.get(i)).remaining();
        }
        return remaining;
    }
    private void applyFeedback(LearningBatchItem item,List<LearningBatchItem> entries,int result,LocalDateTime now,int remainingBefore) {
        applyFeedback(item,entries,result,now,remainingBefore,StudyMode.RECOGNITION,false);
    }
    private void applyFeedback(LearningBatchItem item,List<LearningBatchItem> entries,int result,LocalDateTime now,int remainingBefore,
            StudyMode mode,boolean hinted) {
        item.setResult(result); item.setAnsweredAt(now);
        var outcome=reinforcement.feedback(remainingBefore,item.getAnswerCount(),result);
        item.setReinforcementRemaining(outcome.remaining()); item.setCompletionReason(outcome.completionReason());
        boolean done=outcome.done() || mode==StudyMode.FOLLOW_TYPING;
        if(mode==StudyMode.FOLLOW_TYPING) { item.setCompletionReason("FOLLOW_TYPED"); item.setReinforcementRemaining(0); }
        else if(mode==StudyMode.DICTATION && hinted && result==2) {
            done=true; item.setCompletionReason("ASSISTED"); item.setReinforcementRemaining(0);
        }
        item.setLearningStatus(done?"DONE":"RELEARNING"); item.setCompletedAt(done?now:null);
        if(!done) {
            var others=entries.stream().filter(i->!i.getId().equals(item.getId()) && queued(i))
                .sorted(queueOrder()).toList();
            long position=others.isEmpty()?item.getQueueOrder():others.get(Math.min(outcome.otherWords(),others.size())-1).getQueueOrder();
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
    LearningBatchItem find(List<LearningBatchItem> entries,long id) {
        return entries.stream().filter(i->i.getId()==id).findFirst().orElseThrow(()->
            new BusinessException(HttpStatus.NOT_FOUND,"ITEM_NOT_FOUND","学习明细不存在或不属于该批次"));
    }
    private Comparator<LearningBatchItem> queueOrder() {
        return Comparator.comparing(LearningBatchItem::getQueueOrder).thenComparing(LearningBatchItem::getSortOrder)
            .thenComparing(LearningBatchItem::getId);
    }
    Long next(List<LearningBatchItem> entries) {
        return entries.stream().filter(this::queued).min(queueOrder())
            .map(LearningBatchItem::getId).orElse(null);
    }
    private boolean queued(LearningBatchItem item) {
        return "PENDING".equals(item.getLearningStatus()) || "RELEARNING".equals(item.getLearningStatus());
    }
    DailyTaskVO view(LearningBatch batch,List<LearningBatchItem> entries) {
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
            entries.stream().filter(i->"DONE".equals(i.getLearningStatus())
                && (!Objects.equals(i.getResult(),1) || "LIMIT_REACHED".equals(i.getCompletionReason())
                    || "ASSISTED".equals(i.getCompletionReason()))).count(),
            entries.stream().filter(i->"NEW".equals(i.getItemType())).count(),
            entries.stream().filter(i->"REVIEW".equals(i.getItemType())).count(),actionable?next(entries):null,
            batch.getStudyMode(),batch.getModeVersion(),entries.stream().mapToLong(LearningBatchItem::getStudyAttemptCount).sum());
    }
    private DailyAnswerVO answerView(WordReviewLog event,LearningBatch batch,List<LearningBatchItem> entries) {
        var word=words.selectOneById(event.getWordId());
        return new DailyAnswerVO(event.getId(),event.getBatchItemId(),event.getResult(),word.getTranslation(),
            event.getAnsweredAt(),"CORRECTION".equals(event.getEventType()),view(batch,entries));
    }
}
