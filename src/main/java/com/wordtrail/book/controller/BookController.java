package com.wordtrail.book.controller;

import com.wordtrail.book.dto.*;
import com.wordtrail.book.service.BookService;
import com.wordtrail.book.vo.*;
import com.wordtrail.common.response.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BookVO> create(@Valid @RequestBody BookEditRequest request) {
        return ApiResponse.ok(books.create(request));
    }

    @GetMapping("/{bookId}")
    public ApiResponse<BookVO> detail(@PathVariable @Positive long bookId) {
        return ApiResponse.ok(books.detail(bookId));
    }

    @PutMapping("/{bookId}")
    public ApiResponse<BookVO> update(@PathVariable @Positive long bookId,
            @Valid @RequestBody BookEditRequest request) {
        return ApiResponse.ok(books.update(bookId, request));
    }

    @PostMapping("/{bookId}/words")
    public ApiResponse<BookWordsChangeVO> addWords(@PathVariable @Positive long bookId,
            @Valid @RequestBody BookWordIdsRequest request) {
        return ApiResponse.ok(books.addWords(bookId, request));
    }

    @DeleteMapping("/{bookId}/words")
    public ApiResponse<BookWordsChangeVO> removeWords(@PathVariable @Positive long bookId,
            @Valid @RequestBody BookWordIdsRequest request) {
        return ApiResponse.ok(books.removeWords(bookId, request));
    }

    @DeleteMapping("/{bookId}/words/{wordId}")
    public ApiResponse<BookWordsChangeVO> removeWord(@PathVariable @Positive long bookId,
            @PathVariable @Positive long wordId) {
        return ApiResponse.ok(books.removeWords(bookId, new BookWordIdsRequest(java.util.List.of(wordId))));
    }

    @GetMapping("/{bookId}/words")
    public ApiResponse<PageVO<BookWordVO>> words(@PathVariable @Positive long bookId,
                                                @Valid @ModelAttribute BookWordsQuery query) {
        return ApiResponse.ok(books.words(bookId, query));
    }
}
