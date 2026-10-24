package com.wordtrail.sentence.controller;
import com.wordtrail.common.response.*;
import com.wordtrail.sentence.dto.SentenceQuestionQuery;
import com.wordtrail.sentence.service.*;
import com.wordtrail.sentence.vo.SentenceLearningVO.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/sentence-translation") @RequiredArgsConstructor
public class SentenceLearningController {
    private final SentenceReviewService reviews;
    private final SentenceStatisticsService statistics;
    @GetMapping("/reviews/due") public ApiResponse<PageVO<DueQuestion>> due(@Valid @ModelAttribute SentenceQuestionQuery q){return ApiResponse.ok(reviews.due(q));}
    @GetMapping("/questions/{sentenceId}/schedule") public ApiResponse<Schedule> schedule(@PathVariable @Positive long sentenceId){return ApiResponse.ok(reviews.schedule(sentenceId));}
    @GetMapping("/statistics") public ApiResponse<Statistics> stats(@RequestParam(defaultValue="7") @Min(1) @Max(366) int days){return ApiResponse.ok(statistics.statistics(days));}
}
