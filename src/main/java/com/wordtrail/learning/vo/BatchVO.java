package com.wordtrail.learning.vo;

public record BatchVO(Long batchId, Long bookId, String selectionType, String status, int wordCount) {}
