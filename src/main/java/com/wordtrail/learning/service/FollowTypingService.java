package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.entity.*;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.learning.vo.*;
import com.wordtrail.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;

@Service @RequiredArgsConstructor
public class FollowTypingService {
    private final CurrentUser currentUser;
    private final LearningContext context;
    private final LearningBatchMapper batches;
    private final LearningBatchItemMapper items;
    private final FollowTypingMapper selection;
    private final DailyLearningService daily;
    private final LearningStatisticsService statistics;

    @Transactional public DailyTaskVO create(CreateFollowTypingRequest request) {
        long userId=currentUser.id(); context.lockUser(userId);
        String fingerprint;
        try { fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(("FOLLOW_TYPING:v1:"+request.getCount()).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        var previous=batches.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("creation_request_id").eq(request.getRequestId())));
        if(previous!=null) {
            if(!"FOLLOW_TYPING".equals(previous.getPracticeMode()) || !fingerprint.equals(previous.getCreationFingerprint()))
                throw conflict("REQUEST_ID_REUSED","请求标识已用于其他内容");
            return daily.view(previous,context.entries(previous.getId()));
        }
        if(batches.selectCountByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("practice_mode").eq("FOLLOW_TYPING")).and(column("status").eq("IN_PROGRESS")))>0)
            throw conflict("FOLLOW_TYPING_IN_PROGRESS","请先继续或结束进行中的跟拼练习");
        var now=context.now(); var selected=FollowTypingSelector.select(selection.candidates(userId,now.minusHours(24)),
            request.getCount(),now,ThreadLocalRandom.current());
        if(selected.isEmpty()) throw conflict("NO_FOLLOW_TYPING_WORDS","暂无可练习词，请检查有效计划或等待重复练习冷却");
        var batch=new LearningBatch(); batch.setUserId(userId); batch.setTaskType("PRACTICE"); batch.setPracticeMode("FOLLOW_TYPING");
        batch.setSelectionType("MANUAL"); batch.setStudyMode("FOLLOW_TYPING"); batch.setModeVersion(0L);
        batch.setStatus("IN_PROGRESS"); batch.setCreatedAt(now); batch.setUpdatedAt(now);
        batch.setCreationRequestId(request.getRequestId()); batch.setCreationFingerprint(fingerprint); batch.setFollowTypingTarget(request.getCount());
        if(batches.insertSelective(batch)!=1) throw new IllegalStateException("Could not create follow typing practice");
        var entries=new ArrayList<LearningBatchItem>();
        for(var c:selected) {
            var item=new LearningBatchItem(); item.setBatchId(batch.getId()); item.setWordId(c.wordId());
            item.setSortOrder(entries.size()+1); item.setQueueOrder((long)entries.size()+1); item.setLearningStatus("PENDING");
            item.setAnswerCount(0); item.setRelearnCount(0); item.setReinforcementRemaining(0);
            item.setStudyWord(c.word()); item.setStudyPhonetic(c.phonetic()); item.setStudyTranslation(Objects.requireNonNullElse(c.translation(),""));
            entries.add(item);
        }
        if(items.insertBatch(entries)!=entries.size()) throw new IllegalStateException("Could not save follow typing words");
        return daily.view(batch,context.entries(batch.getId()));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<DailyTaskVO> history(BatchQuery query) {
        var page=batches.paginate(query.getPage(),query.getSize(),QueryWrapper.create().where(column("user_id").eq(currentUser.id()))
            .and(column("practice_mode").eq("FOLLOW_TYPING")).and(column("status").eq(query.getStatus().name())).orderBy(column("id").desc()));
        var ids=page.getRecords().stream().map(LearningBatch::getId).toList();
        var grouped=ids.isEmpty()?Map.<Long,List<LearningBatchItem>>of():items.selectListByQuery(QueryWrapper.create().where(column("batch_id").in(ids)))
            .stream().collect(java.util.stream.Collectors.groupingBy(LearningBatchItem::getBatchId));
        return new PageVO<>(query.getPage(),query.getSize(),page.getTotalRow(),page.getRecords().stream()
            .map(b->daily.view(b,grouped.getOrDefault(b.getId(),List.of()))).toList());
    }
    @Transactional public DailyTaskVO abandon(long batchId) {
        long userId=currentUser.id(); context.lockUser(userId); var batch=context.ownedBatch(userId,batchId,true);
        if(!"FOLLOW_TYPING".equals(batch.getPracticeMode())) throw conflict("NOT_FOLLOW_TYPING_TASK","不是独立跟拼练习");
        if("IN_PROGRESS".equals(batch.getStatus())) {
            batch.setStatus("ABANDONED"); batch.setUpdatedAt(context.now());
            if(batches.update(batch)!=1) throw new IllegalStateException("Could not abandon follow typing practice");
        }
        return daily.view(batch,context.entries(batchId));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public FollowTypingStatisticsVO statistics(StatisticsDateQuery query) {
        var date=statistics.date(query.getDate()); return selection.statistics(currentUser.id(),date);
    }
}
