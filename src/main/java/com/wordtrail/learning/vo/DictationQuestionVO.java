package com.wordtrail.learning.vo;

import com.wordtrail.word.vo.TranslationGroupVO;
import java.util.List;

public record DictationQuestionVO(Long itemId,String prompt,List<TranslationGroupVO> promptGroups,
        String presentationId,int nextAttemptNo,int correctStreak,int requiredCorrectStreak,int attemptLimit) {}
