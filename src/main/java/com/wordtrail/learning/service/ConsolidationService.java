package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.entity.*;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.learning.vo.*;
import com.wordtrail.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;

@Service @RequiredArgsConstructor
public class ConsolidationService {
    private final CurrentUser currentUser;
    private final LearningContext context;
    private final ConsolidationMapper issues;
    private final LearningBatchMapper batches;
    private final LearningBatchItemMapper items;
    private final DailyLearningService daily;
    private final ObjectMapper json;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<ConsolidationIssueVO> issues(ConsolidationQuery query) {
        long userId=currentUser.id(),count=issues.count(userId,query.scope(),query.getReason()==null?null:query.getReason().name());
        long offset=((long)query.getPage()-1)*query.getSize();
        return new PageVO<>(query.getPage(),query.getSize(),count,offset>=count?List.of():issues.list(userId,query.scope(),
            query.getReason()==null?null:query.getReason().name(),offset,query.getSize()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<ConsolidationMapper.ReasonCount> summary() {
        var found=new HashMap<String,ConsolidationMapper.ReasonCount>(); for(var value:issues.summary(currentUser.id())) found.put(value.reason(),value);
        return Arrays.stream(ConsolidationQuery.Reason.values()).map(reason->found.getOrDefault(reason.name(),
            new ConsolidationMapper.ReasonCount(reason.name(),reason==ConsolidationQuery.Reason.PENDING_VERIFICATION?"VERIFICATION":"ERROR",0))).toList();
    }
    @Transactional public DailyTaskVO create(CreateConsolidationRequest request) {
        long userId=currentUser.id(); context.lockUser(userId);
        List<Long> ids=request.getWordIds()==null?null:new ArrayList<>(new LinkedHashSet<>(request.getWordIds()));
        var fingerprint=new LinkedHashMap<String,Object>(); fingerprint.put("category",request.scope()); fingerprint.put("reason",request.getReason());
        fingerprint.put("mode",request.getMode()); fingerprint.put("wordIds",ids); fingerprint.put("count",ids==null?Objects.requireNonNullElse(request.getCount(),20):null);
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(fingerprint).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        var old=batches.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId)).and(column("creation_request_id").eq(request.getRequestId())));
        if(old!=null) {
            if(!hash.equals(old.getCreationFingerprint()) || !"CONSOLIDATION".equals(old.getPracticeMode())) throw conflict("REQUEST_ID_REUSED","请求标识已用于其他内容");
            return daily.view(old,context.entries(old.getId()));
        }
        var selected=issues.candidates(userId,request.scope(),request.getReason()==null?null:request.getReason().name(),ids,
            ids==null?Objects.requireNonNullElse(request.getCount(),20):ids.size());
        if(ids!=null) {
            if(selected.size()!=ids.size()) throw conflict("CONSOLIDATION_WORD_UNAVAILABLE","所选词须全部属于本人当前筛选的待巩固清单");
            var indexed=new HashMap<Long,ConsolidationIssueVO>(); selected.forEach(value->indexed.put(value.wordId(),value));
            selected=ids.stream().map(indexed::get).toList();
        }
        if(selected.isEmpty()) throw conflict("NO_CONSOLIDATION_WORDS","当前没有符合筛选的待巩固词");
        if(request.getMode()==StudyMode.DICTATION && selected.stream().anyMatch(value->value.translation()==null || value.translation().isBlank()))
            throw conflict("TRAINING_PROMPT_UNAVAILABLE","部分词缺少中文释义，请换用听音或跟拼");
        var now=context.now(); var batch=new LearningBatch(); batch.setUserId(userId); batch.setTaskType("PRACTICE");
        batch.setPracticeMode("CONSOLIDATION"); batch.setSelectionType("MANUAL"); batch.setStudyMode(request.getMode().name());
        batch.setModeVersion(0L); batch.setStatus("IN_PROGRESS"); batch.setCreatedAt(now); batch.setUpdatedAt(now);
        batch.setCreationRequestId(request.getRequestId()); batch.setCreationFingerprint(hash);
        batch.setConsolidationReason(request.getReason()==null?null:request.getReason().name());
        if(batches.insertSelective(batch)!=1) throw new IllegalStateException("Could not create consolidation task");
        var entries=new ArrayList<LearningBatchItem>();
        for(var value:selected) {
            var item=new LearningBatchItem(); item.setBatchId(batch.getId()); item.setWordId(value.wordId()); item.setSortOrder(entries.size()+1);
            item.setQueueOrder((long)entries.size()+1); item.setItemType("REVIEW"); item.setLearningStatus("PENDING");
            item.setAnswerCount(0); item.setRelearnCount(0); item.setReinforcementRemaining(0);
            item.setStudyWord(value.word()); item.setStudyTranslation(Objects.requireNonNullElse(value.translation(),"")); entries.add(item);
        }
        if(items.insertBatch(entries)!=entries.size()) throw new IllegalStateException("Could not save consolidation word list");
        return daily.view(batch,context.entries(batch.getId()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<DailyTaskVO> history(BatchQuery query) {
        var page=batches.paginate(query.getPage(),query.getSize(),QueryWrapper.create().where(column("user_id").eq(currentUser.id()))
            .and(column("practice_mode").eq("CONSOLIDATION")).and(column("status").eq(query.getStatus().name()))
            .orderBy(column("id").desc()));
        var ids=page.getRecords().stream().map(LearningBatch::getId).toList();
        var grouped=ids.isEmpty()?Map.<Long,List<LearningBatchItem>>of():items.selectListByQuery(QueryWrapper.create().where(column("batch_id").in(ids)))
            .stream().collect(java.util.stream.Collectors.groupingBy(LearningBatchItem::getBatchId));
        return new PageVO<>(query.getPage(),query.getSize(),page.getTotalRow(),page.getRecords().stream().map(b->daily.view(b,grouped.getOrDefault(b.getId(),List.of()))).toList());
    }
}
