package com.wordtrail.book.controller;

import com.wordtrail.book.dto.*;
import com.wordtrail.book.service.BookService;
import com.wordtrail.book.vo.*;
import com.wordtrail.common.response.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/word-books")
@RequiredArgsConstructor
public class BookController {
    private final BookService books;

    @GetMapping
    public ApiResponse<PageVO<BookVO>> list(@Valid @ModelAttribute BookQuery query) {
        return ApiResponse.ok(books.list(query));
    }

    @GetMapping("/{bookId}/words")
    public ApiResponse<PageVO<BookWordVO>> words(@PathVariable @Positive long bookId,
                                                @Valid @ModelAttribute BookWordsQuery query) {
        return ApiResponse.ok(books.words(bookId, query));
    }
}
