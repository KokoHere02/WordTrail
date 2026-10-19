package com.wordtrail.learning.vo;

import java.time.LocalDateTime;

public record BatchResultVO(Long batchId, Long bookId, String bookName, long totalCount,
                            long knownCount, long uncertainCount, long unknownCount,
                            long relearnCount, LocalDateTime completedAt) {}
