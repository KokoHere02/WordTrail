package com.wordtrail.learning.service;

import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.mapper.LearningStatisticsMapper;
import com.wordtrail.learning.vo.*;
import com.wordtrail.security.CurrentUser;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class LearningStatisticsService {
    private final LearningStatisticsMapper statistics;
    private final CurrentUser currentUser;
    private final LearningContext context;

    public DailyStatisticsVO daily(StatisticsDateQuery query) {
        long userId = currentUser.id();
        LocalDate date = date(query.getDate());
        var rows = statistics.dailyStatistics(userId, date, date);
        return rows.isEmpty() ? empty(date) : rows.getFirst();
    }

    public LearningCalendarVO calendar(StatisticsMonthQuery query) {
        long userId = currentUser.id();
        YearMonth month = month(query.getMonth());
        var rows = statistics.dailyStatistics(userId, month.atDay(1), month.atEndOfMonth()).stream()
            .collect(Collectors.toMap(DailyStatisticsVO::learningDate, Function.identity()));
        var days = new ArrayList<CalendarDayVO>(month.lengthOfMonth());
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            var row = rows.getOrDefault(date, empty(date));
            days.add(new CalendarDayVO(date, row.completedCount(), row.answerCount(),
                row.hasLearningActivity(), row.statisticsComplete(), row.incompleteWordCount(),row.trainingCount(),row.recalledCount()));
        }
        return new LearningCalendarVO(month.toString(), days.stream().allMatch(CalendarDayVO::statisticsComplete), days);
    }

    public PageVO<StatisticsWordVO> words(StatisticsWordsQuery query) {
        long userId = currentUser.id();
        LocalDate date = date(query.getDate());
        long count = statistics.countStudiedWords(userId, date, date);
        long offset = ((long) query.getPage() - 1) * query.getSize();
        var records = offset >= count ? List.<StatisticsWordVO>of()
            : statistics.studiedWords(userId, date, date, offset, query.getSize());
        return new PageVO<>(query.getPage(), query.getSize(), count, records);
    }

    LocalDate date(String value) {
        if (value == null) { return context.today(); }
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw invalid("date 必须是有效日期，格式为 YYYY-MM-DD"); }
    }

    private YearMonth month(String value) {
        if (value == null) { return YearMonth.from(context.today()); }
        try { return YearMonth.parse(value); }
        catch (DateTimeParseException ex) { throw invalid("month 必须是有效月份，格式为 YYYY-MM"); }
    }

    private DailyStatisticsVO empty(LocalDate date) {
        return new DailyStatisticsVO(date, 0, 0, 0, 0, 0, false, true, 0,0,0,0);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }
}
