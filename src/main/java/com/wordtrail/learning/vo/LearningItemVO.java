package com.wordtrail.learning.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import com.wordtrail.word.vo.TranslatedWord;
import com.wordtrail.word.vo.TranslationGroupVO;

public record LearningItemVO(Long itemId, Long wordId, String word, String phonetic, int sortOrder,
                             Integer result, boolean answered,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String translation,
                             LocalDateTime answeredAt, Long nextItemId) implements TranslatedWord {
    @Override @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<TranslationGroupVO> getTranslationGroups() {
        return translation == null ? null : TranslatedWord.super.getTranslationGroups();
    }
}
