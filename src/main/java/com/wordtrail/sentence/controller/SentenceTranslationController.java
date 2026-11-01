package com.wordtrail.sentence.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.sentence.dto.SentenceFavoriteRequest;
import com.wordtrail.sentence.service.SentenceFavoriteService;
import com.wordtrail.sentence.vo.SentenceTranslationVO.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/sentence-translation") @RequiredArgsConstructor
public class SentenceTranslationController {
    private final SentenceFavoriteService favorites;
    @PutMapping("/questions/{sentenceId}/favorite")
    public ApiResponse<Favorite> favorite(@PathVariable @Positive long sentenceId,@Valid @RequestBody SentenceFavoriteRequest request) { return ApiResponse.ok(favorites.set(sentenceId,request.favorite())); }
}
