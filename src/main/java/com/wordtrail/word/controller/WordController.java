package com.wordtrail.word.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.word.dto.WordSearchQuery;
import com.wordtrail.word.service.WordService;
import com.wordtrail.word.vo.DictionaryWordVO;
import com.wordtrail.word.vo.WordDetailVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/words")
@RequiredArgsConstructor
public class WordController {
    private final WordService words;
    private final JdbcTemplate db;

    public record WordForm(String form, String formType) {}

    @GetMapping("/{wordId}/forms")
    public ApiResponse<List<WordForm>> forms(@PathVariable @Positive long wordId) {
        return ApiResponse.ok(db.query("SELECT form,form_type FROM word_form WHERE word_id=? ORDER BY form_type,form,id",
            (row, index) -> new WordForm(row.getString(1), row.getString(2)), wordId));
    }

    @GetMapping("/{wordId}")
    public ApiResponse<WordDetailVO> detail(@PathVariable @Positive long wordId) {
        return ApiResponse.ok(words.detail(wordId));
    }

    @GetMapping
    public ApiResponse<PageVO<DictionaryWordVO>> search(@Valid @ModelAttribute WordSearchQuery query) {
        var page = words.search(query);
        return page.total() == 0 ? new ApiResponse<>("OK", "词库中未找到匹配单词，请检查拼写", page) : ApiResponse.ok(page);
    }
}
