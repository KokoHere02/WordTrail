package com.wordtrail.learning.vo;

import java.time.LocalDateTime;
import java.util.List;

public record WeakWordsVO(int page, int size, long total, List<WeakWordVO> records,
        String source, String dataScope, boolean statisticsComplete, long incompleteWordCount,
        LocalDateTime firstRecordedAt, LocalDateTime lastRecordedAt) {}
