package com.wordtrail.learning.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.service.LearningStatisticsService;
import com.wordtrail.learning.service.LearningInsightsService;
import com.wordtrail.learning.vo.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/statistics")
@RequiredArgsConstructor
public class LearningStatisticsController {
    private final LearningStatisticsService statistics;
    private final LearningInsightsService insights;

    @GetMapping("/trends")
    public ApiResponse<LearningTrendVO> trends(@Valid @ModelAttribute StatisticsTrendQuery query) {
        return ApiResponse.ok(insights.trends(query));
    }

    @GetMapping("/summary")
    public ApiResponse<LearningSummaryVO> summary(@Valid @ModelAttribute StatisticsSourceQuery query) {
        return ApiResponse.ok(insights.summary(query));
    }

    @GetMapping("/daily")
    public ApiResponse<DailyStatisticsVO> daily(@Valid @ModelAttribute StatisticsDateQuery query) {
        return ApiResponse.ok(statistics.daily(query));
    }

    @GetMapping("/calendar")
    public ApiResponse<LearningCalendarVO> calendar(@Valid @ModelAttribute StatisticsMonthQuery query) {
        return ApiResponse.ok(statistics.calendar(query));
    }

    @GetMapping("/words")
    public ApiResponse<PageVO<StatisticsWordVO>> words(@Valid @ModelAttribute StatisticsWordsQuery query) {
        return ApiResponse.ok(statistics.words(query));
    }
}
