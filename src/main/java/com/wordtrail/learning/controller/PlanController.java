package com.wordtrail.learning.controller;

import com.wordtrail.common.response.*;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.service.PlanService;
import com.wordtrail.learning.service.WordHistoryService;
import com.wordtrail.learning.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/learning-plan/words") @RequiredArgsConstructor
public class PlanController {
    private final PlanService plans;
    private final WordHistoryService history;
    @PostMapping public ApiResponse<JoinPlanVO> join(@Valid @RequestBody JoinPlanRequest request) {
        return ApiResponse.ok(plans.join(request));
    }
    @GetMapping public ApiResponse<PageVO<PlanWordVO>> list(@Valid @ModelAttribute PlanQuery query) {
        return ApiResponse.ok(plans.list(query));
    }
    @PatchMapping("/{wordId}") public ApiResponse<Void> status(@PathVariable @Positive long wordId,
            @Valid @RequestBody PlanStatusRequest request) {
        plans.status(wordId, request); return ApiResponse.ok(null);
    }
    @PatchMapping("/status") public ApiResponse<BatchPlanStatusVO> batchStatus(
            @Valid @RequestBody BatchPlanStatusRequest request) {
        return ApiResponse.ok(plans.batchStatus(request));
    }
    @GetMapping("/{wordId}/history") public ApiResponse<PageVO<WordHistoryVO>> history(
            @PathVariable @Positive long wordId, @Valid @ModelAttribute WordHistoryQuery query) {
        return ApiResponse.ok(history.history(wordId, query));
    }
}
