package com.wordtrail.learning.vo;

public record BatchProgressVO(Long batchId, Long bookId, String selectionType, String status,
                              long wordCount, long answeredCount, Long nextItemId) {}
