package com.wordtrail.learning.vo;

import com.wordtrail.word.vo.TranslatedWord;
import java.time.LocalDateTime;
public record ConsolidationIssueVO(Long wordId,String word,String translation,String reason,String category,
        String lastInput,String mode,Long trainingId,Long batchId,Long itemId,LocalDateTime lastOccurredAt,long occurrenceCount)
        implements TranslatedWord {}
