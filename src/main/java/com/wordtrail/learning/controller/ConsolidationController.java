package com.wordtrail.learning.controller;

import com.wordtrail.common.response.*;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.mapper.ConsolidationMapper;
import com.wordtrail.learning.service.*;
import com.wordtrail.learning.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/learning/consolidation") @RequiredArgsConstructor
public class ConsolidationController {
    private final ConsolidationService consolidation;
    private final DailyTrainingService training;
    @GetMapping("/issues") public ApiResponse<PageVO<ConsolidationIssueVO>> issues(@Valid @ModelAttribute ConsolidationQuery query) { return ApiResponse.ok(consolidation.issues(query)); }
    @GetMapping("/summary") public ApiResponse<List<ConsolidationMapper.ReasonCount>> summary() { return ApiResponse.ok(consolidation.summary()); }
    @PostMapping("/batches") public ApiResponse<DailyTaskVO> create(@Valid @RequestBody CreateConsolidationRequest request) { return ApiResponse.ok(consolidation.create(request)); }
    @GetMapping("/batches") public ApiResponse<PageVO<DailyTaskVO>> history(@Valid @ModelAttribute BatchQuery query) { return ApiResponse.ok(consolidation.history(query)); }
    @GetMapping("/batches/{batchId}") public ApiResponse<DailyTaskVO> progress(@PathVariable @Positive long batchId) { return ApiResponse.ok(training.consolidationProgress(batchId)); }
    @PutMapping("/batches/{batchId}/mode") public ApiResponse<DailyTaskVO> mode(@PathVariable @Positive long batchId,@Valid @RequestBody StudyModeRequest request) { return ApiResponse.ok(training.consolidationMode(batchId,request)); }
    @PostMapping("/batches/{batchId}/next") public ApiResponse<TrainingQuestionVO> next(@PathVariable @Positive long batchId,@Valid @RequestBody(required=false) TrainingQuestionRequest request) { return ApiResponse.ok(training.consolidationNext(batchId,request)); }
    @PutMapping("/batches/{batchId}/items/{itemId}/answer") public ApiResponse<TrainingAnswerVO> answer(@PathVariable @Positive long batchId,@PathVariable @Positive long itemId,@Valid @RequestBody TrainingAnswerRequest request) { return ApiResponse.ok(training.consolidationAnswer(batchId,itemId,request)); }
    @GetMapping("/batches/{batchId}/items") public ApiResponse<PageVO<DailyTrainingItemVO>> items(@PathVariable @Positive long batchId,@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(training.consolidationItems(batchId,query)); }
    @GetMapping("/batches/{batchId}/attempts") public ApiResponse<PageVO<TrainingAnswerVO>> attempts(@PathVariable @Positive long batchId,@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(training.consolidationHistory(batchId,query)); }
}
