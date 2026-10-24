package com.wordtrail.learning.controller;

import com.wordtrail.common.response.*;
import com.wordtrail.learning.dto.*;
import com.wordtrail.learning.service.DictationService;
import com.wordtrail.learning.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/dictation") @RequiredArgsConstructor
public class DictationController {
    private final DictationService dictation;
    @PostMapping("/batches") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DictationTaskVO> create(@Valid @RequestBody CreateDictationRequest request) { return ApiResponse.ok(dictation.create(request)); }
    @GetMapping("/batches")
    public ApiResponse<PageVO<DictationTaskVO>> list(@Valid @ModelAttribute BatchQuery query) { return ApiResponse.ok(dictation.list(query)); }
    @GetMapping("/batches/{batchId}")
    public ApiResponse<DictationTaskVO> progress(@PathVariable @Positive long batchId) { return ApiResponse.ok(dictation.progress(batchId)); }
    @PostMapping("/batches/{batchId}/next")
    public ApiResponse<DictationQuestionVO> next(@PathVariable @Positive long batchId,@RequestBody(required=false) RelearnRequest request) { return ApiResponse.ok(dictation.next(batchId)); }
    @PutMapping("/batches/{batchId}/items/{itemId}/answer")
    public ApiResponse<DictationAnswerVO> answer(@PathVariable @Positive long batchId,@PathVariable @Positive long itemId,@Valid @RequestBody DictationAnswerRequest request) { return ApiResponse.ok(dictation.answer(batchId,itemId,request)); }
    @GetMapping("/batches/{batchId}/result/words")
    public ApiResponse<PageVO<DictationWordVO>> result(@PathVariable @Positive long batchId,@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(dictation.result(batchId,query)); }
    @GetMapping("/mistakes")
    public ApiResponse<PageVO<DictationWordVO>> mistakes(@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(dictation.mistakes(query)); }
    @GetMapping("/words/{wordId}/history")
    public ApiResponse<PageVO<DictationHistoryVO>> history(@PathVariable @Positive long wordId,@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(dictation.history(wordId,query)); }
}
