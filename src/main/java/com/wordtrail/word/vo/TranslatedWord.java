package com.wordtrail.word.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wordtrail.word.support.TranslationParser;
import java.util.List;

/** Shared response contract; computed properties leave MyBatis record constructors unchanged. */
public interface TranslatedWord {
    String translation();

    @JsonProperty("translationGroups")
    default List<TranslationGroupVO> getTranslationGroups() {
        return TranslationParser.parse(translation());
    }
}
