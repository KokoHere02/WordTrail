package com.wordtrail.learning.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.mapper.*;
import com.wordtrail.learning.vo.*;
import com.wordtrail.security.CurrentUser;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service @RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class LearningInsightsService {
    private final CurrentUser currentUser;
    private final LearningContext context;
    private final LearningInsightsMapper insights;
    private final LearningStatisticsMapper statistics;
    private final ReviewEvidenceMapper evidence;
    private final LearningBatchMapper batches;
    private final LearningSettingsService settings;

    public LearningTrendVO trends(StatisticsTrendQuery query) {
        long userId = currentUser.id(); var today = context.today();
        var from = today.minusDays(query.getDays()-1L); var source = query.getSource();
        var recorded = insights.recordedDays(userId,source.name(),from,today).stream()
            .collect(Collectors.toMap(LearningInsightsMapper.RecordedDay::learningDate,Function.identity()));
        var completed = dailyCompletions(userId,source,from,today);
        var days = new ArrayList<LearningTrendDayVO>(query.getDays());
        for (var date = from; !date.isAfter(today); date = date.plusDays(1)) {
            var row = recorded.get(date);
            long answers = row == null ? 0 : row.answerCount(), studied = row == null ? 0 : row.studiedCount();
            long finished = source == LearningSource.DAILY ? completed.getOrDefault(date,0L) : studied;
            days.add(new LearningTrendDayVO(date,finished,answers,studied,answers>0));
        }
        return new LearningTrendVO(source.name(),query.getDays(),from,today,
            days.stream().mapToLong(LearningTrendDayVO::completedCount).sum(),
            days.stream().mapToLong(LearningTrendDayVO::answerCount).sum(),coverage(userId,source),days);
    }

    public LearningSummaryVO summary(StatisticsSourceQuery query) {
        long userId = currentUser.id(); var today = context.today(); var source = query.getSource();
        var days = insights.recordedDays(userId,source.name(),null,today);
        long run = 0, longest = 0; LocalDate previous = null;
        for (var day : days) {
            run = previous != null && day.learningDate().equals(previous.plusDays(1)) ? run+1 : 1;
            longest = Math.max(longest,run); previous = day.learningDate();
        }
        long current = previous != null && !previous.isBefore(today.minusDays(1)) ? run : 0;
        var last = days.isEmpty() ? null : days.getLast();
        boolean studiedToday = last != null && last.learningDate().equals(today);
        long answers = studiedToday ? last.answerCount() : 0, studied = studiedToday ? last.studiedCount() : 0;
        long completed = source == LearningSource.DAILY
            ? dailyCompletions(userId,source,today,today).getOrDefault(today,0L) : studied;
        var goal = source == LearningSource.DAILY ? goal(userId,today,completed) : null;
        return new LearningSummaryVO(today,source.name(),current,longest,days.size(),studiedToday,
            studied,completed,answers,goal,coverage(userId,source));
    }

    private Map<LocalDate,Long> dailyCompletions(long userId,LearningSource source,LocalDate from,LocalDate to) {
        return source != LearningSource.DAILY ? Map.of() : statistics.dailyStatistics(userId,from,to).stream()
            .collect(Collectors.toMap(DailyStatisticsVO::learningDate,DailyStatisticsVO::completedCount));
    }

    private LearningSummaryVO.DailyGoal goal(long userId,LocalDate today,long completed) {
        var batch = batches.selectOneByQuery(QueryWrapper.create().where(column("user_id").eq(userId))
            .and(column("task_type").eq("DAILY")).and(column("learning_date").eq(today)));
        int target = batch == null ? settings.dailyTarget(userId) : batch.getDailyTarget();
        return new LearningSummaryVO.DailyGoal(target,completed,Math.max(target-completed,0),completed>=target,
            batch == null ? "SETTING" : "TASK_SNAPSHOT",batch == null ? null : batch.getId(),
            batch == null ? null : batch.getStatus());
    }

    private StatisticsCoverageVO coverage(long userId,LearningSource source) {
        var row = evidence.coverage(userId,source.name(),null);
        return new StatisticsCoverageVO(source == LearningSource.DAILY ? "RECORDED_ANSWERS_AND_DAILY_ITEMS" : "RECORDED_ANSWERS",
            "ALL_RETAINED_SOURCE_ITEMS",row.incompleteWordCount()==0,row.incompleteWordCount(),
            row.firstRecordedAt(),row.lastRecordedAt());
    }
}
