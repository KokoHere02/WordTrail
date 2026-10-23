package com.wordtrail.learning.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.learning.dto.WeakWordsQuery;
import com.wordtrail.learning.dto.WeakPracticeRequest;
import com.wordtrail.learning.service.LearningService;
import com.wordtrail.learning.service.WeakWordService;
import com.wordtrail.learning.vo.BatchVO;
import com.wordtrail.learning.vo.WeakWordsVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/learning/weak-words") @RequiredArgsConstructor
public class WeakWordController {
    private final WeakWordService weakWords;
    private final LearningService learning;

    @GetMapping public ApiResponse<WeakWordsVO> list(@Valid @ModelAttribute WeakWordsQuery query) {
        return ApiResponse.ok(weakWords.list(query));
    }

    @PostMapping("/practice") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BatchVO> practice(@Valid @RequestBody WeakPracticeRequest request) {
        return ApiResponse.ok(learning.createWeakPractice(request));
    }
}
