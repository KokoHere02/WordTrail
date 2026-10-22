package com.wordtrail.book.vo;

public record BookVO(Long id, String name, String description, long wordCount,
        long unlearnedCount, long startedCount, long dueCount, String bookType, boolean editable) {}
