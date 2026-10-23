package com.wordtrail.learning.vo;

import java.time.LocalDateTime;

public record StatisticsCoverageVO(String dataScope, String coverageScope,
        boolean statisticsComplete, long incompleteWordCount,
        LocalDateTime firstRecordedAt, LocalDateTime lastRecordedAt) {}
