package com.wordtrail.learning.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

public record LearningItemVO(Long itemId, Long wordId, String word, String phonetic, int sortOrder,
                             Integer result, boolean answered,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String translation,
                             LocalDateTime answeredAt, Long nextItemId) {}
