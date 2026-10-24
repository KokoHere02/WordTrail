package com.wordtrail.sentence.service;
import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.book.dto.BookWordIdsRequest;
import com.wordtrail.book.service.BookService;
import com.wordtrail.book.vo.BookWordsChangeVO;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.JoinPlanRequest;
import com.wordtrail.learning.service.*;
import com.wordtrail.learning.vo.JoinPlanVO;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.dto.*;
import com.wordtrail.sentence.mapper.*;
import com.wordtrail.sentence.vo.SentenceTranslationVO.*;
import com.wordtrail.word.mapper.WordMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.wordtrail.learning.service.LearningContext.conflict;
@Service @RequiredArgsConstructor
public class SentenceWordBridgeService {
    public record Collected(Long bookId,Long wordId,BookWordsChangeVO collection,JoinPlanVO plan) {}
    private final CurrentUser user;
    private final LearningContext context;
    private final WordMapper words;
    private final SentenceWordBridgeMapper bridge;
    private final SentenceTranslationService learning;
    private final SentenceSessionMapper sessions;
    private final SentenceItemMapper items;
    private final SentenceItemWordMapper links;
    private final BookService books;
    private final PlanService plans;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<Question> related(long wordId,SentenceQuestionQuery query) {
        requireWords(List.of(wordId));query.setWordId(wordId);return learning.questions(query,false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<SentenceWordBridgeMapper.WordCount> counts(List<Long> ids) {
        if(ids.size()>100) throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","每次最多查询 100 个单词");
        var unique=new ArrayList<>(new LinkedHashSet<>(ids));requireWords(unique);var indexed=new HashMap<Long,Long>();for(var row:bridge.counts(unique)) indexed.put(row.wordId(),row.sentenceCount());
        return unique.stream().map(id->new SentenceWordBridgeMapper.WordCount(id,indexed.getOrDefault(id,0L))).toList();
    }
    @Transactional
    public Progress practice(WordSentencePracticeRequest request) {
        context.lockUser(user.id());var ids=new ArrayList<>(new LinkedHashSet<>(request.wordIds()));requireWords(ids);
        var selected=bridge.candidates(ids,request.difficulty(),request.count()==null?10:request.count());
        if(selected.isEmpty()) throw conflict("NO_SENTENCE_QUESTIONS","所选单词暂无关联的可用句子");
        var create=new CreateSentenceSessionRequest();create.setSentenceIds(selected);create.setDifficulty(request.difficulty());return learning.create(create);
    }
    @Transactional
    public Collected collect(long sessionId,long itemId,long wordId,CollectSentenceWordRequest request) {
        long userId=user.id();context.lockUser(userId);
        if(sessions.selectCountByQuery(QueryWrapper.create().where(column("id").eq(sessionId)).and(column("user_id").eq(userId)))!=1)
            throw new BusinessException(HttpStatus.NOT_FOUND,"SENTENCE_SESSION_NOT_FOUND","练习不存在或无权访问");
        if(items.selectCountByQuery(QueryWrapper.create().where(column("id").eq(itemId)).and(column("session_id").eq(sessionId)))!=1
            || links.selectCountByQuery(QueryWrapper.create().where(column("item_id").eq(itemId)).and(column("word_id").eq(wordId)))!=1)
            throw conflict("SENTENCE_WORD_NOT_LINKED","单词不属于该练习题目的关联快照");
        requireWords(List.of(wordId));var collected=books.addWords(request.bookId(),new BookWordIdsRequest(List.of(wordId)));
        var plan=Boolean.TRUE.equals(request.joinPlan())?plans.join(new JoinPlanRequest(request.bookId(),List.of(wordId))):null;
        return new Collected(request.bookId(),wordId,collected,plan);
    }
    private void requireWords(List<Long> ids) {
        if(words.selectCountByQuery(QueryWrapper.create().where(column("id").in(ids)))!=ids.size())
            throw new BusinessException(HttpStatus.NOT_FOUND,"WORD_NOT_FOUND","部分单词不存在");
    }
}
