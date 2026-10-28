package com.wordtrail.learning.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.service.DailyLearningService;
import com.wordtrail.learning.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class DailyLearningController {
    private final DailyLearningService daily;
    @GetMapping("/api/learning/today") public ApiResponse<TodayVO> today() { return ApiResponse.ok(daily.today()); }
    @GetMapping("/api/learning/daily-tasks") public ApiResponse<PageVO<DailyTaskVO>> history(@Valid @ModelAttribute BatchQuery query) {
        return ApiResponse.ok(daily.history(query));
    }
    @PostMapping("/api/learning/today/start") public ApiResponse<DailyTaskVO> start(
            @RequestBody(required=false) EmptyRequest empty) { return ApiResponse.ok(daily.start()); }
    @GetMapping("/api/learning-batches/{batchId}/daily")
    public ApiResponse<DailyTaskVO> progress(@PathVariable @Positive long batchId) { return ApiResponse.ok(daily.progress(batchId)); }
    @GetMapping("/api/learning-batches/{batchId}/daily/items/{itemId}")
    public ApiResponse<DailyItemVO> item(@PathVariable @Positive long batchId,@PathVariable @Positive long itemId) {
        return ApiResponse.ok(daily.item(batchId,itemId));
    }
    @PutMapping("/api/learning-batches/{batchId}/daily/items/{itemId}/answer")
    public ApiResponse<DailyAnswerVO> answer(@PathVariable @Positive long batchId,@PathVariable @Positive long itemId,
            @Valid @RequestBody DailyAnswerRequest request) { return ApiResponse.ok(daily.answer(batchId,itemId,request)); }
    @PutMapping("/api/learning-batches/{batchId}/daily/items/{itemId}/correction")
    public ApiResponse<DailyAnswerVO> correct(@PathVariable @Positive long batchId,@PathVariable @Positive long itemId,
            @Valid @RequestBody CorrectionRequest request) { return ApiResponse.ok(daily.correct(batchId,itemId,request)); }
}
