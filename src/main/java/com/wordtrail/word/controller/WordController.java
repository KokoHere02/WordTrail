package com.wordtrail.word.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.word.dto.WordSearchQuery;
import com.wordtrail.word.service.WordService;
import com.wordtrail.word.vo.DictionaryWordVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/words")
@RequiredArgsConstructor
public class WordController {
    private final WordService words;

    @GetMapping
    public ApiResponse<PageVO<DictionaryWordVO>> search(@Valid @ModelAttribute WordSearchQuery query) {
        var page = words.search(query);
        return page.total() == 0 ? new ApiResponse<>("OK", "词库中未找到匹配单词，请检查拼写", page) : ApiResponse.ok(page);
    }
}
