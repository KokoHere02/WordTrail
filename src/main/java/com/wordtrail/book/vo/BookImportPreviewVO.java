package com.wordtrail.book.vo;

import java.util.List;

public record BookImportPreviewVO(long bookId, String format, int totalCount, int matchedCount,
        int alreadyInBookCount, int duplicateCount, int notFoundCount, int ambiguousCount,
        int invalidCount, int ignoredBlankCount, List<String> ignoredColumns,
        List<Long> readyWordIds, List<BookImportRowVO> records) {}
