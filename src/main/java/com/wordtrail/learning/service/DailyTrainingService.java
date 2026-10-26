package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.entity.*;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.learning.vo.*;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.word.mapper.WordMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;

@Service @RequiredArgsConstructor
public class DailyTrainingService {
    private final CurrentUser currentUser;
    private final LearningContext context;
    private final DailyLearningService daily;
    private final LearningBatchMapper batches;
    private final LearningBatchItemMapper items;
    private final UserWordStateMapper states;
    private final WordMapper words;
    private final WordTrainingAttemptMapper attempts;
    private final ReviewEvents events;
    private final LearningSettingsService settings;
    private final LearningStatisticsService statistics;

    @Transactional
    public DailyTaskVO mode(long batchId,StudyModeRequest request) {
        return mode(batchId,request,false);
    }
    @Transactional public DailyTaskVO consolidationMode(long batchId,StudyModeRequest request) { return mode(batchId,request,true); }
    private DailyTaskVO mode(long batchId,StudyModeRequest request,boolean consolidation) {
        long userId=currentUser.id(); context.lockUser(userId);
        var batch=batch(userId,batchId,true,context.today(),consolidation);
        if(!batch.getModeVersion().equals(request.expectedVersion())) throw conflict("STALE_STUDY_MODE","学习方式已变化，请刷新任务");
        if(!batch.getStudyMode().equals(request.mode().name())) {
            batch.setStudyMode(request.mode().name()); batch.setModeVersion(Math.addExact(batch.getModeVersion(),1));
            batch.setUpdatedAt(context.now());
            if(batches.update(batch)!=1) throw new IllegalStateException("Could not switch study mode");
        }
        if(!consolidation) settings.rememberMode(userId,batch.getStudyMode());
        return daily.view(batch,context.entries(batchId));
    }

    @Transactional
    public TrainingQuestionVO next(long batchId,TrainingQuestionRequest request) {
        return next(batchId,request,false);
    }
    @Transactional public TrainingQuestionVO consolidationNext(long batchId,TrainingQuestionRequest request) { return next(batchId,request,true); }
    private TrainingQuestionVO next(long batchId,TrainingQuestionRequest request,boolean consolidation) {
        long userId=currentUser.id(); context.lockUser(userId);
        var batch=batch(userId,batchId,true,context.today(),consolidation); var entries=context.entries(batchId);
        Long id=request==null || request.itemId()==null ? daily.next(entries) : request.itemId();
        if(id==null) throw conflict("NO_TRAINING_ITEM","本组已完成，可选择已完成的词追加训练");
        var item=daily.find(entries,id); if(!consolidation) daily.availableState(userId,item);
        boolean extra="DONE".equals(item.getLearningStatus());
        if(!extra && !Objects.equals(daily.next(entries),id)) throw conflict("STALE_TRAINING_ITEM","请继续当前词或选择已完成的词追加训练");
        snapshot(item);
        var mode=StudyMode.valueOf(batch.getStudyMode());
        boolean revealed=answerRevealed(userId,item.getId(),mode);
        if(mode==StudyMode.DICTATION && item.getStudyTranslation().isBlank())
            throw conflict("TRAINING_PROMPT_UNAVAILABLE","该词暂无中文释义，请切换学习方式");
        if(item.getStudyPresentationId()==null || !batch.getModeVersion().equals(item.getStudyPresentationVersion())
                || !Objects.equals(item.getStudyExtraTraining(),extra)) {
            item.setStudyPresentationId(UUID.randomUUID().toString()); item.setStudyPresentationVersion(batch.getModeVersion());
            item.setStudyExtraTraining(extra);
        }
        // Persist exposure before returning a visible spelling; switching cannot turn copying into unprompted recall.
        if(!mode.recallInput()) item.setStudyHintUsed(true);
        save(item);
        boolean spellingVisible=!mode.recallInput() || revealed;
        String translation=(mode==StudyMode.RECOGNITION || mode==StudyMode.LISTEN_DICTATION) && !revealed ? null : item.getStudyTranslation();
        if(mode==StudyMode.DICTATION && !revealed) translation=DictationPolicy.prompt(translation,item.getStudyWord());
        String phase=mode==StudyMode.RECOGNITION?(revealed?"AWAITING_SELF_ASSESSMENT":"QUESTION"):"AWAITING_INPUT";
        String audioUrl="/api/learning-batches/"+batchId+"/training/items/"+id+"/audio?presentationId="
            +item.getStudyPresentationId()+"&modeVersion="+batch.getModeVersion();
        return new TrainingQuestionVO(id,mode,batch.getModeVersion(),item.getStudyPresentationId(),item.getStudyAttemptCount()+1,
            spellingVisible?item.getWordId():null,spellingVisible?item.getStudyWord():null,
            spellingVisible?item.getStudyPhonetic():null,translation,item.getStudyHintUsed(),extra,revealed,phase,
            item.getLearningStatus(),item.getCompletionReason(),daily.view(batch,entries),audioUrl,settings.pronunciation(userId));
    }

    @Transactional
    public TrainingAnswerVO answer(long batchId,long itemId,TrainingAnswerRequest request) {
        return answer(batchId,itemId,request,false);
    }
    @Transactional public TrainingAnswerVO consolidationAnswer(long batchId,long itemId,TrainingAnswerRequest request) { return answer(batchId,itemId,request,true); }
    private TrainingAnswerVO answer(long batchId,long itemId,TrainingAnswerRequest request,boolean consolidation) {
        long userId=currentUser.id(); context.lockUser(userId);
        var now=context.now();
        var batch=batch(userId,batchId,true,now.toLocalDate(),consolidation); var entries=context.entries(batchId);
        var item=daily.find(entries,itemId);
        var previous=attempts.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId)).and(column("request_id").eq(request.requestId())));
        if(previous!=null) {
            if(previous.getBatchId()!=batchId || previous.getBatchItemId()!=itemId
                    || !Objects.equals(previous.getPresentationId(),request.presentationId())
                    || !Objects.equals(previous.getModeVersion(),request.modeVersion())
                    || !Objects.equals(previous.getAttemptNo(),request.attemptNo())
                    || !previous.getAction().equals(request.action().name())
                    || !Objects.equals(previous.getInputText(),request.input())
                    || !Objects.equals(previous.getSelfAssessment(),request.result())) throw conflict("REQUEST_ID_REUSED","请求标识已用于其他内容");
            return response(previous,item,progress(batchId,consolidation));
        }
        if(events.findRequest(userId,request.requestId())!=null) throw conflict("REQUEST_ID_REUSED","请求标识已用于其他作答");
        if(!consolidation) daily.availableState(userId,item);
        if(!batch.getModeVersion().equals(request.modeVersion()) || !Objects.equals(item.getStudyPresentationVersion(),request.modeVersion())
                || !Objects.equals(item.getStudyPresentationId(),request.presentationId())
                || request.attemptNo()!=item.getStudyAttemptCount()+1) throw conflict("STALE_TRAINING_ANSWER","题目、方式或作答次数已变化，请重新获取题目");
        boolean extra=Boolean.TRUE.equals(item.getStudyExtraTraining());
        if(extra ? !"DONE".equals(item.getLearningStatus()) : !Objects.equals(daily.next(entries),itemId))
            throw conflict("STALE_TRAINING_ANSWER","当前任务进度已变化，请重新获取题目");
        var mode=StudyMode.valueOf(batch.getStudyMode()); boolean reveal=request.action()==TrainingAnswerRequest.Action.REVEAL;
        validate(request,mode,reveal);
        if(reveal && answerRevealed(userId,itemId,mode)) throw conflict("ANSWER_ALREADY_REVEALED","答案已展示，请继续作答，无需重复查看");
        boolean hinted=Boolean.TRUE.equals(item.getStudyHintUsed()) || reveal || mode==StudyMode.FOLLOW_TYPING;
        boolean correct=!reveal && mode!=StudyMode.RECOGNITION
            && item.getStudyWord().strip().equalsIgnoreCase(request.input().strip());
        String verdict=reveal?"REVEALED":mode==StudyMode.RECOGNITION?"SELF_ASSESSED":correct?"CORRECT":"INCORRECT";
        Long logId=null;
        // A reveal and an incorrect copy are training events, without completing a queue item.
        if(!extra && !reveal && (mode!=StudyMode.FOLLOW_TYPING || correct)) {
            int result=mode==StudyMode.RECOGNITION?request.result():mode==StudyMode.FOLLOW_TYPING?2:correct?(hinted?2:1):3;
            if(!consolidation) {
                var feedback=daily.answerInMode(batchId,itemId,new DailyAnswerRequest(result,request.requestId(),item.getAnswerCount()+1),mode,hinted,true,now);
                logId=feedback.logId();
            } else {
                if(item.getAnswerCount()>=DailyReinforcementPolicy.MAX_ANSWERS) throw conflict("STALE_TRAINING_ANSWER","本词已达到强化上限");
                item.setAnswerCount(item.getAnswerCount()+1); item.setRelearnCount(Math.max(0,item.getAnswerCount()-1));
                daily.applyFeedback(item,entries,result,now,Objects.requireNonNullElse(item.getReinforcementRemaining(),0),mode,hinted);
                daily.updateBatch(batch,entries,now);
            }
        }
        // Reload because effective feedback updates the same item and queue in this transaction.
        item=items.selectOneById(itemId);
        var attempt=new WordTrainingAttempt(); attempt.setUserId(userId); attempt.setWordId(item.getWordId());
        attempt.setBatchId(batchId); attempt.setBatchItemId(itemId); attempt.setRequestId(request.requestId());
        attempt.setPresentationId(request.presentationId()); attempt.setModeVersion(request.modeVersion()); attempt.setMode(mode.name());
        attempt.setAction(request.action().name()); attempt.setInputText(request.input()); attempt.setSelfAssessment(request.result());
        attempt.setVerdict(verdict); attempt.setHintUsed(hinted); attempt.setExtraTraining(extra);
        attempt.setAttemptNo(request.attemptNo()); attempt.setReviewLogId(logId);
        attempt.setLearningDate(now.toLocalDate()); attempt.setAnsweredAt(now);
        if(attempts.insertSelective(attempt)!=1) throw new IllegalStateException("Could not save training attempt");
        item.setStudyAttemptCount(request.attemptNo()); item.setStudyPresentationId(null); item.setStudyPresentationVersion(null);
        // Every response contains the reference answer, so subsequent dictation is assisted for this task.
        item.setStudyHintUsed(true); save(item);
        return response(attempt,item,progress(batchId,consolidation));
    }

    private void validate(TrainingAnswerRequest request,StudyMode mode,boolean reveal) {
        boolean valid=reveal ? request.input()==null && request.result()==null
            : mode==StudyMode.RECOGNITION ? request.input()==null && request.result()!=null
            : request.result()==null && request.input()!=null && !request.input().isBlank();
        if(!valid) throw conflict("INVALID_TRAINING_ANSWER","REVEAL 不接受答案；认识模式只接受 result；输入模式只接受 input");
    }

    private void snapshot(LearningBatchItem item) {
        if(item.getStudyWord()!=null) {
            if(item.getStudyTranslation()==null) item.setStudyTranslation("");
            return;
        }
        // An unfinished pre-upgrade item is snapshotted on first presentation; no historical evidence is fabricated.
        var word=words.selectOneById(item.getWordId());
        item.setStudyWord(word.getWord()); item.setStudyTranslation(word.getTranslation()==null?"":word.getTranslation());
        item.setStudyPhonetic(word.getPhonetic());
        if(item.getAnswerCount()>0) item.setStudyHintUsed(true);
    }
    private boolean answerRevealed(long userId,long itemId,StudyMode mode) {
        var latest=attempts.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("batch_item_id").eq(itemId)).orderBy(column("id").desc()).limit(1));
        return latest!=null && "REVEAL".equals(latest.getAction()) && mode.name().equals(latest.getMode());
    }
    private void save(LearningBatchItem item) {
        if(items.updateByQuery(item,false,QueryWrapper.create().where(column("id").eq(item.getId())))!=1)
            throw new IllegalStateException("Could not persist training presentation");
    }
    private TrainingAnswerVO response(WordTrainingAttempt attempt,LearningBatchItem item,DailyTaskVO progress) {
        var feedback=attempt.getReviewLogId()==null?null:events.findRequest(attempt.getUserId(),attempt.getRequestId());
        return new TrainingAnswerVO(attempt.getId(),item.getId(),item.getWordId(),attempt.getMode(),attempt.getAction(),attempt.getVerdict(),
            attempt.getInputText(),attempt.getSelfAssessment(),item.getStudyWord(),item.getStudyTranslation(),attempt.getHintUsed(),
            attempt.getExtraTraining(),attempt.getAttemptNo(),attempt.getReviewLogId(),feedback!=null && feedback.getAffectsSchedule(),
            attempt.getAnsweredAt(),item.getLearningStatus(),item.getCompletionReason(),progress);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<DailyTrainingItemVO> listItems(long batchId,WordHistoryQuery query) {
        return listItems(batchId,query,false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<DailyTrainingItemVO> consolidationItems(long batchId,WordHistoryQuery query) { return listItems(batchId,query,true); }
    private PageVO<DailyTrainingItemVO> listItems(long batchId,WordHistoryQuery query,boolean consolidation) {
        var today=context.today(); var batch=batch(currentUser.id(),batchId,false,null,consolidation);
        var page=items.paginate(query.getPage(),query.getSize(),QueryWrapper.create().where(column("batch_id").eq(batchId))
            .orderBy(column("sort_order").asc(),column("id").asc()));
        var progress=progress(batchId,consolidation);
        var doneIds=page.getRecords().stream().filter(i->"DONE".equals(i.getLearningStatus())).map(LearningBatchItem::getWordId).toList();
        var activeIds=doneIds.isEmpty()?Set.<Long>of():new HashSet<>(states.selectListByQuery(QueryWrapper.create()
            .where(column("user_id").eq(currentUser.id())).and(column("plan_status").eq("ACTIVE"))
            .and(column("word_id").in(doneIds))).stream().map(UserWordState::getWordId).toList());
        var legacyIds=page.getRecords().stream().filter(i->"DONE".equals(i.getLearningStatus()) && i.getStudyWord()==null)
            .map(LearningBatchItem::getWordId).toList();
        var legacyWords=legacyIds.isEmpty()?Map.<Long,com.wordtrail.word.entity.Word>of():words.selectListByQuery(
            QueryWrapper.create().where(column("id").in(legacyIds))).stream().collect(java.util.stream.Collectors.toMap(
                com.wordtrail.word.entity.Word::getId,java.util.function.Function.identity()));
        return new PageVO<>(query.getPage(),query.getSize(),page.getTotalRow(),page.getRecords().stream().map(item->{
            boolean done="DONE".equals(item.getLearningStatus());
            // Only already completed items expose dictionary identities/answers in a list.
            // Showing any pending spelling here would bypass persisted exposure tracking.
            boolean actionable=done && (consolidation || today.equals(batch.getLearningDate()) && activeIds.contains(item.getWordId()));
            var legacy=legacyWords.get(item.getWordId());
            return new DailyTrainingItemVO(item.getId(),done?item.getWordId():null,
                done?(legacy==null?item.getStudyWord():legacy.getWord()):null,
                done?(legacy==null?item.getStudyPhonetic():legacy.getPhonetic()):null,
                done?(legacy==null?item.getStudyTranslation():legacy.getTranslation()):null,
                item.getSortOrder(),item.getItemType(),item.getLearningStatus(),item.getCompletionReason(),
                item.getAnswerCount(),item.getStudyAttemptCount(),item.getCompletedAt(),
                Objects.equals(progress.nextItemId(),item.getId()),actionable);
        }).toList());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<TrainingAnswerVO> history(long batchId,WordHistoryQuery query) {
        return history(batchId,query,false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<TrainingAnswerVO> consolidationHistory(long batchId,WordHistoryQuery query) { return history(batchId,query,true); }
    private PageVO<TrainingAnswerVO> history(long batchId,WordHistoryQuery query,boolean consolidation) {
        batch(currentUser.id(),batchId,false,null,consolidation); var entries=context.entries(batchId);
        var page=attempts.paginate(query.getPage(),query.getSize(),QueryWrapper.create().where(column("user_id").eq(currentUser.id()))
            .and(column("batch_id").eq(batchId)).orderBy(column("id").desc()));
        var progress=progress(batchId,consolidation);
        return new PageVO<>(query.getPage(),query.getSize(),page.getTotalRow(),page.getRecords().stream()
            .map(a->response(a,daily.find(entries,a.getBatchItemId()),progress)).toList());
    }

    private LearningBatch batch(long userId,long batchId,boolean lock,java.time.LocalDate date,boolean consolidation) {
        if(!consolidation) return daily.daily(userId,batchId,lock,date);
        var batch=context.ownedBatch(userId,batchId,lock);
        if(!"PRACTICE".equals(batch.getTaskType()) || !"CONSOLIDATION".equals(batch.getPracticeMode())) throw conflict("NOT_CONSOLIDATION_TASK","请使用该任务原有接口");
        if("ABANDONED".equals(batch.getStatus())) throw conflict("BATCH_ABANDONED","该练习已放弃");
        return batch;
    }
    private DailyTaskVO progress(long batchId,boolean consolidation) {
        if(!consolidation) return daily.progress(batchId);
        return daily.view(batch(currentUser.id(),batchId,false,null,true),context.entries(batchId));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DailyTaskVO consolidationProgress(long batchId) { return progress(batchId,true); }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TrainingStatisticsVO statistics(StatisticsDateQuery query) {
        var day=statistics.daily(query);
        var recorded=new HashMap<String,TrainingStatisticsVO.ModeStatistics>();
        for(var row:attempts.statistics(currentUser.id(),day.learningDate())) recorded.put(row.mode(),row);
        var modes=Arrays.stream(StudyMode.values()).map(Enum::name).map(mode->recorded.getOrDefault(mode,
            new TrainingStatisticsVO.ModeStatistics(mode,0,0,0,0,null,0,0,0,0))).toList();
        return new TrainingStatisticsVO(day.learningDate(),day.completedCount(),modes);
    }
}
