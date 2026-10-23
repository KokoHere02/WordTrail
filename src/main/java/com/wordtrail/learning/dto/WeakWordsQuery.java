package com.wordtrail.learning.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class WeakWordsQuery extends WordHistoryQuery {
    @NotNull private LearningSource source = LearningSource.DAILY;
}
