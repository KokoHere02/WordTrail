package com.wordtrail.sentence.service;
import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.service.LearningContext;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.dto.SentenceQuestionQuery;
import com.wordtrail.sentence.entity.*;
import com.wordtrail.sentence.mapper.SentenceReviewStateMapper;
import com.wordtrail.sentence.vo.SentenceLearningVO.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service @RequiredArgsConstructor
public class SentenceReviewService {
    private static final int[] INTERVALS={1,3,7,14,30};
    private final CurrentUser user;
    private final LearningContext context;
    private final SentenceReviewStateMapper states;
    // Invoked in the self-review transaction, after the existing user write lock.
    @Transactional(propagation=Propagation.MANDATORY)
    public void reviewed(SentenceTranslationAttempt attempt) {
        var state=find(attempt.getUserId(),attempt.getSentenceId());
        if(state!=null && state.getLastAttemptId()>=attempt.getId()) return;
        boolean understood="UNDERSTOOD".equals(attempt.getSelfAssessment());
        int level=understood?Math.min(5,(state==null?0:state.getReviewLevel())+1):0;
        if(state==null){state=new SentenceReviewState();state.setUserId(attempt.getUserId());state.setSentenceId(attempt.getSentenceId());}
        state.setLastAttemptId(attempt.getId());state.setReviewLevel(level);state.setLastAssessment(attempt.getSelfAssessment());
        state.setReviewedAt(attempt.getReviewedAt());state.setNextReviewDate(attempt.getReviewDate().plusDays(understood?INTERVALS[level-1]:1));
        int n=state.getId()==null?states.insertSelective(state):states.update(state);
        if(n!=1) throw new IllegalStateException("Could not persist sentence review schedule");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Schedule schedule(long sentenceId) {var state=find(user.id(),sentenceId);return state==null?null:new Schedule(state.getSentenceId(),state.getLastAttemptId(),state.getReviewLevel(),state.getLastAssessment(),state.getReviewedAt(),state.getNextReviewDate());}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<DueQuestion> due(SentenceQuestionQuery query) {
        var today=context.today();long total=states.countDue(user.id(),today,query.getDifficulty(),query.getWordId());long offset=((long)query.getPage()-1)*query.getSize();
        return new PageVO<>(query.getPage(),query.getSize(),total,offset>=total?List.of():states.due(user.id(),today,query.getDifficulty(),query.getWordId(),offset,query.getSize()));
    }
    private SentenceReviewState find(long userId,long sentenceId){return states.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId)).and(column("sentence_id").eq(sentenceId)));}
}
