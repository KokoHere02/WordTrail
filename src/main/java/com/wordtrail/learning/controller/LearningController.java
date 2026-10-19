package com.wordtrail.learning.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.learning.dto.CreateBatchRequest;
import com.wordtrail.learning.dto.AnswerRequest;
import com.wordtrail.learning.dto.BatchQuery;
import com.wordtrail.learning.dto.ResultWordsQuery;
import com.wordtrail.learning.dto.RelearnRequest;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.service.LearningService;
import com.wordtrail.learning.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning-batches")
@RequiredArgsConstructor
public class LearningController {
    private final LearningService learning;

    @GetMapping
    public ApiResponse<PageVO<BatchListVO>> list(@Valid @ModelAttribute BatchQuery query) {
        return ApiResponse.ok(learning.list(query));
    }

    @GetMapping("/{batchId}")
    public ApiResponse<BatchProgressVO> progress(@PathVariable @Positive long batchId) {
        return ApiResponse.ok(learning.progress(batchId));
    }

    @GetMapping("/{batchId}/items/{itemId}")
    public ApiResponse<LearningItemVO> item(@PathVariable @Positive long batchId,
                                          @PathVariable @Positive long itemId) {
        return ApiResponse.ok(learning.item(batchId, itemId));
    }

    @GetMapping("/{batchId}/result")
    public ApiResponse<BatchResultVO> result(@PathVariable @Positive long batchId) {
        return ApiResponse.ok(learning.result(batchId));
    }

    @GetMapping("/{batchId}/result/words")
    public ApiResponse<PageVO<ResultWordVO>> resultWords(@PathVariable @Positive long batchId,
            @Valid @ModelAttribute ResultWordsQuery query) {
        return ApiResponse.ok(learning.resultWords(batchId, query));
    }

    @PostMapping("/{batchId}/relearn")
    public ResponseEntity<ApiResponse<BatchVO>> relearn(@PathVariable @Positive long batchId,
            @RequestBody(required = false) RelearnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(learning.relearn(batchId)));
    }

    @PutMapping("/{batchId}/items/{itemId}/answer")
    public ApiResponse<AnswerVO> answer(@PathVariable @Positive long batchId,
                                      @PathVariable @Positive long itemId,
                                      @Valid @RequestBody AnswerRequest request) {
        return ApiResponse.ok(learning.answer(batchId, itemId, request));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BatchVO>> create(@Valid @RequestBody CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(learning.create(request)));
    }
}
