package com.wordtrail.learning.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.learning.dto.LearningRecordQuery;
import com.wordtrail.learning.service.LearningRecordsService;
import com.wordtrail.learning.vo.LearningRecordVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class LearningRecordsController {
    private final LearningRecordsService records;

    @GetMapping("/api/learning/records")
    public ApiResponse<PageVO<LearningRecordVO>> list(@Valid @ModelAttribute LearningRecordQuery query) {
        return ApiResponse.ok(records.list(query));
    }
}
