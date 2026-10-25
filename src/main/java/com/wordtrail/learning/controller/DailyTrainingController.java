package com.wordtrail.learning.controller;

import com.wordtrail.common.response.*;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.service.DailyTrainingService;
import com.wordtrail.learning.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class DailyTrainingController {
    private final DailyTrainingService training;

    @GetMapping("/api/learning-batches/{batchId}/daily/training/items")
    public ApiResponse<PageVO<DailyTrainingItemVO>> items(@PathVariable @Positive long batchId,@Valid @ModelAttribute WordHistoryQuery query) {
        return ApiResponse.ok(training.listItems(batchId,query));
    }

    @PutMapping("/api/learning-batches/{batchId}/daily/mode")
    public ApiResponse<DailyTaskVO> mode(@PathVariable @Positive long batchId,@Valid @RequestBody StudyModeRequest request) {
        return ApiResponse.ok(training.mode(batchId,request));
    }
    @PostMapping("/api/learning-batches/{batchId}/daily/training/next")
    public ApiResponse<TrainingQuestionVO> next(@PathVariable @Positive long batchId,
            @Valid @RequestBody(required=false) TrainingQuestionRequest request) {
        return ApiResponse.ok(training.next(batchId,request));
    }
    @PutMapping("/api/learning-batches/{batchId}/daily/items/{itemId}/training-answer")
    public ApiResponse<TrainingAnswerVO> answer(@PathVariable @Positive long batchId,@PathVariable @Positive long itemId,
            @Valid @RequestBody TrainingAnswerRequest request) {
        return ApiResponse.ok(training.answer(batchId,itemId,request));
    }
    @GetMapping("/api/learning-batches/{batchId}/daily/training/attempts")
    public ApiResponse<PageVO<TrainingAnswerVO>> history(@PathVariable @Positive long batchId,@Valid @ModelAttribute WordHistoryQuery query) {
        return ApiResponse.ok(training.history(batchId,query));
    }
    @GetMapping("/api/learning/statistics/training")
    public ApiResponse<TrainingStatisticsVO> statistics(@Valid @ModelAttribute StatisticsDateQuery query) {
        return ApiResponse.ok(training.statistics(query));
    }
}
