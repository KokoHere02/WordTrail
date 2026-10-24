package com.wordtrail.sentence.controller;
import com.wordtrail.common.response.*;
import com.wordtrail.book.dto.BookWordIdsRequest;
import com.wordtrail.sentence.dto.*;
import com.wordtrail.sentence.mapper.SentenceWordBridgeMapper.WordCount;
import com.wordtrail.sentence.service.SentenceWordBridgeService;
import com.wordtrail.sentence.vo.SentenceTranslationVO.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class SentenceWordBridgeController {
    private final SentenceWordBridgeService service;
    @GetMapping("/api/words/{wordId}/sentences") public ApiResponse<PageVO<Question>> related(@PathVariable @Positive long wordId,@Valid @ModelAttribute SentenceQuestionQuery query){return ApiResponse.ok(service.related(wordId,query));}
    @PostMapping("/api/sentence-translation/word-links/summary") public ApiResponse<List<WordCount>> counts(@Valid @RequestBody BookWordIdsRequest request){return ApiResponse.ok(service.counts(request.wordIds()));}
    @PostMapping("/api/sentence-translation/word-practice") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<Progress> practice(@Valid @RequestBody WordSentencePracticeRequest request){return ApiResponse.ok(service.practice(request));}
    @PostMapping("/api/sentence-translation/sessions/{sessionId}/items/{itemId}/words/{wordId}/collect")
    public ApiResponse<SentenceWordBridgeService.Collected> collect(@PathVariable @Positive long sessionId,@PathVariable @Positive long itemId,@PathVariable @Positive long wordId,@Valid @RequestBody CollectSentenceWordRequest request){return ApiResponse.ok(service.collect(sessionId,itemId,wordId,request));}
}
