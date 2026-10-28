package com.wordtrail.learning.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.learning.dto.WeakWordsQuery;
import com.wordtrail.learning.service.WeakWordService;
import com.wordtrail.learning.vo.WeakWordsVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/learning/weak-words") @RequiredArgsConstructor
public class WeakWordController {
    private final WeakWordService weakWords;

    @GetMapping public ApiResponse<WeakWordsVO> list(@Valid @ModelAttribute WeakWordsQuery query) {
        return ApiResponse.ok(weakWords.list(query));
    }
}
