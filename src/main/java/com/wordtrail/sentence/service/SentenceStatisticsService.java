package com.wordtrail.sentence.service;
import com.wordtrail.learning.service.LearningContext;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.sentence.dto.SentenceActivityQuery;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.mapper.*;
import com.wordtrail.sentence.vo.SentenceLearningVO.*;
import com.wordtrail.sentence.vo.SentenceLearningVO.Calendar;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
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
        var dates=stats.learningDates(userId); long run=0,longest=0; LocalDate previous=null;
        for(var date:dates) {
            if(date.isAfter(today)) break;
            run=previous!=null && date.equals(previous.plusDays(1))?run+1:1;
            longest=Math.max(longest,run); previous=date;
        }
        long current=previous!=null && !previous.isBefore(today.minusDays(1))?run:0;
        return new Statistics(totals.submissionCount(),totals.answerCount(),totals.revealCount(),totals.selfReviewCount(),attempts.countReviewQueue(userId),states.countDue(userId,today,null,null),dates.size(),from,today,List.copyOf(result),current,longest);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Calendar calendar(String value) {
        YearMonth month=month(value);
        var indexed=new HashMap<LocalDate,Day>();
        for(var row:stats.days(user.id(),month.atDay(1),month.atEndOfMonth())) indexed.put(row.learningDate(),row);
        var result=new ArrayList<ActivityDay>(month.lengthOfMonth());
        for(int day=1;day<=month.lengthOfMonth();day++) {
            var date=month.atDay(day); result.add(activityDay(date,indexed.get(date)));
        }
        return new Calendar(month.toString(),List.copyOf(result));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ActivityDay daily(String value) {
        var date=date(value); var rows=stats.days(user.id(),date,date);
        return activityDay(date,rows.isEmpty()?null:rows.getFirst());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageVO<ActivityDetail> activity(SentenceActivityQuery query) {
        long userId=user.id(); var date=date(query.getDate());
        long count=attempts.countDateActivity(userId,date), offset=((long)query.getPage()-1)*query.getSize();
        var rows=offset>=count?List.<ActivityDetail>of():attempts.dateActivity(userId,date,offset,query.getSize());
        return new PageVO<>(query.getPage(),query.getSize(),count,rows);
    }

    private ActivityDay activityDay(LocalDate date,Day day) {
        return day==null?new ActivityDay(date,false,0,0,0,0):
            new ActivityDay(date,day.submissionCount()>0 || day.selfReviewCount()>0,
                day.submissionCount(),day.answerCount(),day.revealCount(),day.selfReviewCount());
    }
    private LocalDate date(String value) {
        if(value==null) return context.today();
        try { return LocalDate.parse(value); }
        catch(DateTimeParseException ex) { throw invalid("date 必须是有效日期，格式为 YYYY-MM-DD"); }
    }
    private YearMonth month(String value) {
        if(value==null) return YearMonth.from(context.today());
        try { return YearMonth.parse(value); }
        catch(DateTimeParseException ex) { throw invalid("month 必须是有效月份，格式为 YYYY-MM"); }
    }
    private BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER",message); }
}
