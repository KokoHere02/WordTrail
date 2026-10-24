package com.wordtrail.sentence.service;
import com.wordtrail.learning.service.LearningContext;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.mapper.*;
import com.wordtrail.sentence.vo.SentenceLearningVO.*;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @RequiredArgsConstructor
public class SentenceStatisticsService {
    private final CurrentUser user;
    private final LearningContext context;
    private final SentenceStatisticsMapper stats;
    private final SentenceAttemptMapper attempts;
    private final SentenceReviewStateMapper states;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Statistics statistics(int days) {
        long userId=user.id();var today=context.today();var from=today.minusDays(days-1);var totals=stats.totals(userId);
        var indexed=new HashMap<LocalDate,Day>();for(var row:stats.days(userId,from,today)) indexed.put(row.learningDate(),row);
        var result=new ArrayList<Day>();for(var date=from;!date.isAfter(today);date=date.plusDays(1)) result.add(indexed.getOrDefault(date,new Day(date,0,0,0,0)));
        return new Statistics(totals.submissionCount(),totals.answerCount(),totals.revealCount(),totals.selfReviewCount(),attempts.countReviewQueue(userId),states.countDue(userId,today,null,null),stats.learningDays(userId),from,today,List.copyOf(result));
    }
}
