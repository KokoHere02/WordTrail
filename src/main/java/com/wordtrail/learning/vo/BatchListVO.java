package com.wordtrail.learning.vo;

import java.time.LocalDateTime;

public record BatchListVO(Long batchId, Long bookId, String bookName, String selectionType,
                          String status, long totalCount, long answeredCount, Long nextItemId,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {}
