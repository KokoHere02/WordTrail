package com.wordtrail.sentence.controller;

import com.wordtrail.common.response.*;
import com.wordtrail.learning.dto.*;
import com.wordtrail.sentence.dto.*;
import com.wordtrail.sentence.service.SentenceTranslationService;
import com.wordtrail.sentence.vo.SentenceTranslationVO.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/sentence-translation") @RequiredArgsConstructor
public class SentenceTranslationController {
    private final SentenceTranslationService service;
    @GetMapping("/questions")
    public ApiResponse<PageVO<Question>> questions(@Valid @ModelAttribute SentenceQuestionQuery query) { return ApiResponse.ok(service.questions(query,false)); }
    @GetMapping("/favorites")
    public ApiResponse<PageVO<Question>> favorites(@Valid @ModelAttribute SentenceQuestionQuery query) { return ApiResponse.ok(service.questions(query,true)); }
    @PutMapping("/questions/{sentenceId}/favorite")
    public ApiResponse<Favorite> favorite(@PathVariable @Positive long sentenceId,@Valid @RequestBody SentenceFavoriteRequest request) { return ApiResponse.ok(service.favorite(sentenceId,request.favorite())); }
    @GetMapping("/review-queue")
    public ApiResponse<PageVO<ReviewWord>> reviewQueue(@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(service.reviewQueue(query)); }
    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Progress> create(@Valid @RequestBody CreateSentenceSessionRequest request) { return ApiResponse.ok(service.create(request)); }
    @GetMapping("/sessions")
    public ApiResponse<PageVO<Progress>> list(@Valid @ModelAttribute BatchQuery query) { return ApiResponse.ok(service.list(query)); }
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<Progress> progress(@PathVariable @Positive long sessionId) { return ApiResponse.ok(service.progress(sessionId)); }
    @GetMapping("/sessions/{sessionId}/current")
    public ApiResponse<Current> current(@PathVariable @Positive long sessionId) { return ApiResponse.ok(service.current(sessionId)); }
    @PutMapping("/sessions/{sessionId}/items/{itemId}/submission")
    public ApiResponse<Feedback> submit(@PathVariable @Positive long sessionId,@PathVariable @Positive long itemId,@Valid @RequestBody SentenceSubmissionRequest request) { return ApiResponse.ok(service.submit(sessionId,itemId,request)); }
    @PutMapping("/sessions/{sessionId}/items/{itemId}/self-review")
    public ApiResponse<Feedback> review(@PathVariable @Positive long sessionId,@PathVariable @Positive long itemId,@Valid @RequestBody SentenceSelfReviewRequest request) { return ApiResponse.ok(service.review(sessionId,itemId,request)); }
    @GetMapping("/sessions/{sessionId}/result")
    public ApiResponse<PageVO<History>> result(@PathVariable @Positive long sessionId,@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(service.result(sessionId,query)); }
    @GetMapping("/questions/{sentenceId}/history")
    public ApiResponse<PageVO<History>> history(@PathVariable @Positive long sentenceId,@Valid @ModelAttribute WordHistoryQuery query) { return ApiResponse.ok(service.history(sentenceId,query)); }
}
