package com.wordtrail.book.controller;

import com.wordtrail.book.dto.BookImportRequest;
import com.wordtrail.book.service.BookTransferService;
import com.wordtrail.book.vo.BookImportPreviewVO;
import com.wordtrail.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/word-books/{bookId}") @RequiredArgsConstructor
public class BookTransferController {
    private final BookTransferService transfer;

    @PostMapping(value="/import/preview",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<BookImportPreviewVO> preview(@PathVariable @Positive long bookId,@Valid @RequestBody BookImportRequest request) {
        return ApiResponse.ok(transfer.preview(bookId,request.text(),request.format()));
    }
    @PostMapping(value="/import/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<BookImportPreviewVO> previewFile(@PathVariable @Positive long bookId,@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(transfer.previewFile(bookId,file));
    }
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable @Positive long bookId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("wordtrail-book-"+bookId+".csv").build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(transfer.export(bookId));
    }
}
