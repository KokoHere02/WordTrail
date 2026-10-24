package com.wordtrail.sentence.controller;
import com.wordtrail.common.response.*;
import com.wordtrail.sentence.dto.*;
import com.wordtrail.sentence.service.SentenceNoteService;
import com.wordtrail.sentence.vo.SentenceNoteVO.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/sentence-translation") @RequiredArgsConstructor
public class SentenceNoteController {
    private final SentenceNoteService service;
    @GetMapping("/questions/{sentenceId}/note") public ApiResponse<Note> get(@PathVariable @Positive long sentenceId){return ApiResponse.ok(service.get(sentenceId));}
    @PutMapping("/questions/{sentenceId}/note") public ApiResponse<Note> save(@PathVariable @Positive long sentenceId,@Valid @RequestBody SentenceNoteRequest request){return ApiResponse.ok(service.save(sentenceId,request));}
    @GetMapping("/notes") public ApiResponse<PageVO<Entry>> list(@Valid @ModelAttribute SentenceNoteQuery query){return ApiResponse.ok(service.list(query));}
}
