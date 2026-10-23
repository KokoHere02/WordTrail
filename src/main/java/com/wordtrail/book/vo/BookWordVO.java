package com.wordtrail.book.vo;

import com.wordtrail.word.vo.TranslatedWord;

public record BookWordVO(Long id, String word, String phonetic, String translation,
                         boolean learned, Integer lastResult) implements TranslatedWord {}
