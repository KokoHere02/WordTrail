package com.wordtrail.learning.controller;

import com.wordtrail.common.response.*;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.service.*;
import com.wordtrail.learning.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/learning/follow-typing") @RequiredArgsConstructor
public class FollowTypingController {
    private final FollowTypingService follow;
    private final DailyTrainingService training;
    @PostMapping("/batches") public ApiResponse<DailyTaskVO> create(@Valid @RequestBody CreateFollowTypingRequest request) { return ApiResponse.ok(follow.create(request)); }
    @GetMapping("/batches") public ApiResponse<PageVO<DailyTaskVO>> history(@Valid @ModelAttribute BatchQuery query) { return ApiResponse.ok(follow.history(query)); }
    @GetMapping("/batches/{batchId}") public ApiResponse<DailyTaskVO> progress(@PathVariable @Positive long batchId) { return ApiResponse.ok(training.followTypingProgress(batchId)); }
    @PostMapping("/batches/{batchId}/next") public ApiResponse<TrainingQuestionVO> next(@PathVariable @Positive long batchId,@Valid @RequestBody(required=false) TrainingQuestionRequest request) { return ApiResponse.ok(training.followTypingNext(batchId,request)); }
    @PutMapping("/batches/{batchId}/items/{itemId}/answer") public ApiResponse<TrainingAnswerVO> answer(@PathVariable @Positive long batchId,@PathVariable @Positive long itemId,@Valid @RequestBody TrainingAnswerRequest request) { return ApiResponse.ok(training.followTypingAnswer(batchId,itemId,request)); }
    @GetMapping("/batches/{batchId}/items") public ApiResponse<PageVO<DailyTrainingItemVO>> items(@PathVariable @Positive long batchId,@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(training.followTypingItems(batchId,query)); }
    @GetMapping("/batches/{batchId}/attempts") public ApiResponse<PageVO<TrainingAnswerVO>> attempts(@PathVariable @Positive long batchId,@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(training.followTypingHistory(batchId,query)); }
    @PostMapping("/batches/{batchId}/abandon") public ApiResponse<DailyTaskVO> abandon(@PathVariable @Positive long batchId) { return ApiResponse.ok(follow.abandon(batchId)); }
    @GetMapping("/statistics") public ApiResponse<FollowTypingStatisticsVO> statistics(@Valid @ModelAttribute StatisticsDateQuery query) { return ApiResponse.ok(follow.statistics(query)); }
}
