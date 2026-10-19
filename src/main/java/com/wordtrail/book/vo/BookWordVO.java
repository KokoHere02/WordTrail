package com.wordtrail.book.vo;

public record BookWordVO(Long id, String word, String phonetic, String translation,
                         boolean learned, Integer lastResult) {}
