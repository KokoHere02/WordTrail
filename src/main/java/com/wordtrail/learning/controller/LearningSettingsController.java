package com.wordtrail.learning.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.learning.dto.LearningSettingsRequest;
import com.wordtrail.learning.service.LearningSettingsService;
import com.wordtrail.learning.vo.LearningSettingsVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/settings")
@RequiredArgsConstructor
public class LearningSettingsController {
    private final LearningSettingsService settings;

    @GetMapping
    public ApiResponse<LearningSettingsVO> get() { return ApiResponse.ok(settings.get()); }

    @PutMapping
    public ApiResponse<LearningSettingsVO> update(@Valid @RequestBody LearningSettingsRequest request) {
        return ApiResponse.ok(settings.update(request));
    }
}
