package com.wordtrail.learning.vo;

public record LearningSettingsVO(int dailyTarget, String preferredStudyMode,Integer newWordTarget,Integer reviewLimit,
        PronunciationVO pronunciation) {}
