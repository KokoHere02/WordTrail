package com.wordtrail.word.controller;

import com.wordtrail.common.response.ApiResponse;
import com.wordtrail.common.response.PageVO;
import com.wordtrail.word.dto.WordSearchQuery;
import com.wordtrail.word.service.WordService;
import com.wordtrail.word.vo.DictionaryWordVO;
import com.wordtrail.word.vo.WordDetailVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/words")
@RequiredArgsConstructor
public class WordController {
    private final WordService words;
    private final JdbcTemplate db;

    public record WordForm(String form, String formType) {}
    public record LookupWord(long id, String word, String phonetic, String translation) {}

    @GetMapping("/{wordId}/confusables")
    public ApiResponse<List<LookupWord>> confusables(@PathVariable @Positive long wordId) {
        return ApiResponse.ok(db.query("""
            SELECT w.id,w.word,w.phonetic,w.translation FROM word_confusable_pair p
            JOIN word w ON w.id = CASE WHEN p.word_id_a = ? THEN p.word_id_b ELSE p.word_id_a END
            WHERE (p.word_id_a = ? OR p.word_id_b = ?) AND p.status = 'ACTIVE'
            ORDER BY p.similarity_score DESC, w.word, w.id LIMIT 8
            """, (row, index) -> new LookupWord(row.getLong(1), row.getString(2), row.getString(3), row.getString(4)),
            wordId, wordId, wordId));
    }

    @GetMapping("/lookup")
    public ApiResponse<List<LookupWord>> lookup(@RequestParam @NotBlank @Size(max = 255) String term) {
        String spelling = term.strip().replace('’', '\'');
        String normalized = spelling.toLowerCase(Locale.ROOT);
        return ApiResponse.ok(db.query("""
            SELECT w.id,w.word,w.phonetic,w.translation FROM word w
            WHERE w.word IN (?,?) OR w.id IN (SELECT f.word_id FROM word_form f WHERE f.form IN (?,?))
            ORDER BY CASE WHEN w.word IN (?,?) THEN 0 ELSE 1 END,w.id LIMIT 20
            """, (row, index) -> new LookupWord(row.getLong(1), row.getString(2), row.getString(3), row.getString(4)),
            spelling, normalized, spelling, normalized, spelling, normalized));
    }

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
