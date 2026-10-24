package com.wordtrail.sentence.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.service.LearningContext;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.dto.*;
import com.wordtrail.sentence.entity.*;
import com.wordtrail.sentence.mapper.*;
import com.wordtrail.sentence.vo.SentenceTranslationVO.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;

@Service @RequiredArgsConstructor
public class SentenceTranslationService {
    private final CurrentUser currentUser;
    private final LearningContext context;
    private final SentenceQuestionMapper questions;
    private final SentenceSessionMapper sessions;
    private final SentenceItemMapper items;
    private final SentenceAttemptMapper attempts;
    private final SentenceFavoriteMapper favorites;
    private final SentenceReviewStateMapper reviewStates;
    private final SentenceReviewService reviews;
    private final SentenceItemWordMapper itemWords;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<Question> questions(SentenceQuestionQuery query,boolean onlyFavorites) {
        long userId=currentUser.id(); String source=onlyFavorites?"FAVORITES":"BANK";
        long total=questions.countQuestions(userId,source,query.getDifficulty(),query.getWordId());
        long offset=offset(query);
        return new PageVO<>(query.getPage(),query.getSize(),total,offset>=total?List.of():
            questions.questions(userId,source,query.getDifficulty(),query.getWordId(),offset,query.getSize()));
    }

    @Transactional
    public Favorite favorite(long sentenceId,boolean favorite) {
        long userId=currentUser.id(); context.lockUser(userId);
        var sentence=questions.selectOneById(sentenceId);
        var where=QueryWrapper.create().where(column("user_id").eq(userId)).and(column("sentence_id").eq(sentenceId));
        if(sentence==null || (favorite && !"ACTIVE".equals(sentence.getStatus()))) throw missing("SENTENCE_NOT_FOUND","句子不存在或不可用");
        var existing=favorites.selectOneByQuery(where);
        if(favorite && existing==null) {
            var entry=new SentenceFavorite(); entry.setUserId(userId); entry.setSentenceId(sentenceId); entry.setCreatedAt(context.now());
            requireWrite(favorites.insertSelective(entry));
        } else if(!favorite && existing!=null) requireWrite(favorites.deleteById(existing.getId()));
        return new Favorite(sentenceId,favorite);
    }

    @Transactional
    public Progress create(CreateSentenceSessionRequest request) {
        long userId=currentUser.id(); context.lockUser(userId);
        var selected=request.getSentenceIds()==null?null:new ArrayList<>(new LinkedHashSet<>(request.getSentenceIds()));
        if(selected!=null && (selected.isEmpty() || request.getCount()!=null))
            throw conflict("INVALID_SENTENCE_SELECTION","手选句子不能为空，且不能同时指定 count");
        int count=selected==null?(request.getCount()==null?10:request.getCount()):selected.size();
        var candidates=request.getSource()==CreateSentenceSessionRequest.Source.DUE
            ?reviewStates.candidates(userId,context.today(),request.getDifficulty(),request.getWordId(),selected,count)
            :questions.candidates(userId,request.getSource().name(),request.getDifficulty(),request.getWordId(),selected,count);
        if(selected!=null) {
            if(candidates.size()!=selected.size()) throw conflict("SENTENCE_UNAVAILABLE","所选句子须全部满足来源和筛选条件，请刷新选题");
            var indexed=candidates.stream().collect(Collectors.toMap(SentenceQuestion::getId,Function.identity()));
            candidates=selected.stream().map(indexed::get).toList();
        }
        if(candidates.isEmpty()) throw conflict("NO_SENTENCE_QUESTIONS","暂无符合条件的句子，请先添加题库或调整筛选条件");
        var session=new SentenceSession(); session.setUserId(userId); session.setSelectionSource(request.getSource().name());
        session.setStatus("IN_PROGRESS"); session.setCreatedAt(context.now()); requireWrite(sessions.insertSelective(session));
        var entries=new ArrayList<SentenceSessionItem>();
        for(var candidate:candidates) {
            var entry=new SentenceSessionItem(); entry.setSessionId(session.getId()); entry.setSentenceId(candidate.getId());
            entry.setSortOrder(entries.size()+1); entry.setStatus("PENDING"); entry.setEnglishText(candidate.getEnglishText());
            entry.setReferenceTranslation(candidate.getReferenceTranslation()); entry.setExplanation(candidate.getExplanation());
            entry.setDifficulty(candidate.getDifficulty()); entry.setSource(candidate.getSource()); entry.setSourceReference(candidate.getSourceReference());
            entries.add(entry);
        }
        if(items.insertBatch(entries)!=entries.size()) throw new IllegalStateException("Could not persist all sentence items");
        entries=new ArrayList<>(entries(session.getId()));
        var entryIds=entries.stream().collect(Collectors.toMap(SentenceSessionItem::getSentenceId,SentenceSessionItem::getId));
        var snapshots=new ArrayList<SentenceItemWord>();
        for(var word:questions.linkedForQuestions(new ArrayList<>(entryIds.keySet()))) {
            var link=new SentenceItemWord();link.setItemId(entryIds.get(word.sentenceId()));link.setWordId(word.wordId());link.setWord(word.word());snapshots.add(link);
        }
        if(!snapshots.isEmpty() && itemWords.insertBatch(snapshots)!=snapshots.size()) throw new IllegalStateException("Could not snapshot sentence words");
        return progress(session,entries,List.of());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Progress progress(long sessionId) {
        var session=owned(sessionId); return progress(session,entries(sessionId),records(sessionId));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Current current(long sessionId) {
        owned(sessionId); var entry=next(entries(sessionId));
        if(entry==null) return null;
        var attempt=attempts.selectOneByQuery(QueryWrapper.create().where(column("item_id").eq(entry.getId()))
            .and(column("user_id").eq(currentUser.id())));
        return new Current(entry.getId(),entry.getSentenceId(),entry.getStatus(),entry.getEnglishText(),entry.getDifficulty(),
            entry.getSource(),entry.getSourceReference(),itemWords.selectListByQuery(QueryWrapper.create().where(column("item_id").eq(entry.getId())).orderBy(column("word_id").asc())).stream().map(w->new LinkedWord(w.getWordId(),w.getWord())).toList(),
            attempt==null?null:attempt.getId(),attempt==null?null:attempt.getAction(),attempt==null?null:attempt.getTranslation(),
            attempt==null?null:entry.getReferenceTranslation(),attempt==null?null:entry.getExplanation(),
            attempt==null?null:attempt.getSelfAssessment(),attempt==null?null:attempt.getSubmittedAt());
    }

    @Transactional
    public Feedback submit(long sessionId,long itemId,SentenceSubmissionRequest request) {
        long userId=currentUser.id(); context.lockUser(userId); var session=owned(sessionId);
        var entries=entries(sessionId); var item=item(entries,itemId);
        boolean reveal=request.action()==SentenceSubmissionRequest.Action.REVEAL;
        if(reveal?request.translation()!=null:request.translation()==null || request.translation().isBlank())
            throw conflict("INVALID_SENTENCE_SUBMISSION","翻译作答须输入译文；查看解析须省略 translation");
        var previous=attempts.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("submit_request_id").eq(request.requestId())));
        if(previous!=null) {
            if(previous.getItemId()!=itemId || previous.getSessionId()!=sessionId || !previous.getAction().equals(request.action().name())
                || !Objects.equals(previous.getTranslation(),request.translation())) throw reused();
            return feedback(previous,item,progress(session,entries,records(sessionId)));
        }
        if(!"IN_PROGRESS".equals(session.getStatus()) || next(entries)==null || next(entries).getId()!=itemId || !"PENDING".equals(item.getStatus()))
            throw conflict("STALE_SENTENCE_ITEM","当前题目已变化或已提交，请重新获取当前题目");
        var now=context.now(); var attempt=new SentenceTranslationAttempt();
        attempt.setUserId(userId); attempt.setSessionId(sessionId); attempt.setItemId(itemId); attempt.setSentenceId(item.getSentenceId());
        attempt.setSubmitRequestId(request.requestId()); attempt.setAction(request.action().name()); attempt.setTranslation(request.translation());
        attempt.setLearningDate(now.toLocalDate()); attempt.setSubmittedAt(now); requireWrite(attempts.insertSelective(attempt));
        item.setStatus("AWAITING_REVIEW"); requireWrite(items.update(item));
        return feedback(attempt,item,progress(session,entries,records(sessionId)));
    }

    @Transactional
    public Feedback review(long sessionId,long itemId,SentenceSelfReviewRequest request) {
        long userId=currentUser.id(); context.lockUser(userId); var session=owned(sessionId);
        var entries=entries(sessionId); var item=item(entries,itemId);
        var previous=attempts.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("review_request_id").eq(request.requestId())));
        if(previous!=null) {
            if(previous.getItemId()!=itemId || previous.getSessionId()!=sessionId || !previous.getSelfAssessment().equals(request.assessment().name())) throw reused();
            return feedback(previous,item,progress(session,entries,records(sessionId)));
        }
        if(!"IN_PROGRESS".equals(session.getStatus()) || next(entries)==null || next(entries).getId()!=itemId || !"AWAITING_REVIEW".equals(item.getStatus()))
            throw conflict("SELF_REVIEW_NOT_ALLOWED","请先提交当前题目，再完成一次自评；已保存的自评不允许覆盖");
        var attempt=attempts.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId)).and(column("item_id").eq(itemId)));
        if(attempt==null) throw new IllegalStateException("Missing sentence submission");
        var now=context.now(); attempt.setSelfAssessment(request.assessment().name()); attempt.setReviewRequestId(request.requestId());
        attempt.setReviewDate(now.toLocalDate()); attempt.setReviewedAt(now); requireWrite(attempts.update(attempt));
        reviews.reviewed(attempt);
        item.setStatus("DONE"); requireWrite(items.update(item));
        if(next(entries)==null) { session.setStatus("COMPLETED"); session.setCompletedAt(now); requireWrite(sessions.update(session)); }
        return feedback(attempt,item,progress(session,entries,records(sessionId)));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<Progress> list(BatchQuery query) {
        var page=sessions.paginate(query.getPage(),query.getSize(),QueryWrapper.create().where(column("user_id").eq(currentUser.id()))
            .and(column("status").eq(query.getStatus().name())).orderBy(column("created_at").desc(),column("id").desc()));
        var ids=page.getRecords().stream().map(SentenceSession::getId).toList();
        if(ids.isEmpty()) return new PageVO<>(query.getPage(),query.getSize(),page.getTotalRow(),List.of());
        var entries=items.selectListByQuery(QueryWrapper.create().where(column("session_id").in(ids)).orderBy(column("sort_order").asc()))
            .stream().collect(Collectors.groupingBy(SentenceSessionItem::getSessionId));
        var logs=attempts.selectListByQuery(QueryWrapper.create().where(column("user_id").eq(currentUser.id())).and(column("session_id").in(ids)))
            .stream().collect(Collectors.groupingBy(SentenceTranslationAttempt::getSessionId));
        return new PageVO<>(query.getPage(),query.getSize(),page.getTotalRow(),page.getRecords().stream()
            .map(session->progress(session,entries.getOrDefault(session.getId(),List.of()),logs.getOrDefault(session.getId(),List.of()))).toList());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<History> result(long sessionId,WordHistoryQuery query) {
        var session=owned(sessionId);
        if(!"COMPLETED".equals(session.getStatus())) throw conflict("SENTENCE_SESSION_NOT_COMPLETED","请完成本轮自评后再查看整轮结果");
        return history(null,sessionId,query);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<History> history(long sentenceId,WordHistoryQuery query) { return history(sentenceId,null,query); }
    private PageVO<History> history(Long sentenceId,Long sessionId,WordHistoryQuery query) {
        long total=attempts.countHistory(currentUser.id(),sentenceId,sessionId); long offset=offset(query);
        return new PageVO<>(query.getPage(),query.getSize(),total,offset>=total?List.of():
            attempts.history(currentUser.id(),sentenceId,sessionId,offset,query.getSize()));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<ReviewWord> reviewQueue(WordHistoryQuery query) {
        long total=attempts.countReviewQueue(currentUser.id()); long offset=offset(query);
        return new PageVO<>(query.getPage(),query.getSize(),total,offset>=total?List.of():attempts.reviewQueue(currentUser.id(),offset,query.getSize()));
    }

    private SentenceSession owned(long sessionId) {
        var session=sessions.selectOneByQuery(QueryWrapper.create().where(column("id").eq(sessionId)).and(column("user_id").eq(currentUser.id())));
        if(session==null) throw missing("SENTENCE_SESSION_NOT_FOUND","句子练习不存在或无权访问");
        return session;
    }
    private List<SentenceSessionItem> entries(long sessionId) {
        return items.selectListByQuery(QueryWrapper.create().where(column("session_id").eq(sessionId)).orderBy(column("sort_order").asc(),column("id").asc()));
    }
    private List<SentenceTranslationAttempt> records(long sessionId) {
        return attempts.selectListByQuery(QueryWrapper.create().where(column("session_id").eq(sessionId)).and(column("user_id").eq(currentUser.id())));
    }
    private SentenceSessionItem item(List<SentenceSessionItem> entries,long itemId) {
        return entries.stream().filter(entry->entry.getId()==itemId).findFirst()
            .orElseThrow(()->conflict("SENTENCE_ITEM_NOT_FOUND","题目不属于本轮练习"));
    }
    private SentenceSessionItem next(List<SentenceSessionItem> entries) {
        return entries.stream().filter(entry->!"DONE".equals(entry.getStatus())).findFirst().orElse(null);
    }
    private Progress progress(SentenceSession session,List<SentenceSessionItem> entries,List<SentenceTranslationAttempt> logs) {
        long reviewed=logs.stream().filter(log->log.getSelfAssessment()!=null).count(); var next=next(entries);
        return new Progress(session.getId(),session.getSelectionSource(),session.getStatus(),entries.size(),logs.size(),
            logs.stream().filter(log->"ANSWER".equals(log.getAction())).count(),logs.stream().filter(log->"REVEAL".equals(log.getAction())).count(),
            reviewed,entries.stream().filter(entry->"DONE".equals(entry.getStatus())).count(),
            assessmentCount(logs,"UNDERSTOOD"),assessmentCount(logs,"PARTIAL"),assessmentCount(logs,"NOT_UNDERSTOOD"),
            next==null?null:next.getId(),session.getCreatedAt(),session.getCompletedAt());
    }
    private long assessmentCount(List<SentenceTranslationAttempt> logs,String assessment) {
        return logs.stream().filter(log->assessment.equals(log.getSelfAssessment())).count();
    }
    private Feedback feedback(SentenceTranslationAttempt attempt,SentenceSessionItem item,Progress progress) {
        return new Feedback(attempt.getId(),item.getId(),item.getSentenceId(),attempt.getAction(),attempt.getTranslation(),
            item.getReferenceTranslation(),item.getExplanation(),attempt.getSelfAssessment(),attempt.getLearningDate(),attempt.getSubmittedAt(),
            attempt.getReviewDate(),attempt.getReviewedAt(),progress,reviews.schedule(item.getSentenceId()));
    }
    private static long offset(WordHistoryQuery query) { return ((long)query.getPage()-1)*query.getSize(); }
    private static BusinessException missing(String code,String message) { return new BusinessException(HttpStatus.NOT_FOUND,code,message); }
    private static BusinessException reused() { return conflict("REQUEST_ID_REUSED","该请求标识已用于其他内容"); }
    private static void requireWrite(int affected) { if(affected!=1) throw new IllegalStateException("Could not persist sentence exercise"); }
}
