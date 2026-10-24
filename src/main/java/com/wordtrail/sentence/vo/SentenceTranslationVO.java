package com.wordtrail.sentence.vo;

import java.time.*;
import java.util.List;

public final class SentenceTranslationVO {
    private SentenceTranslationVO() {}
    public record Question(Long sentenceId,String englishText,Integer difficulty,String source,String sourceReference,boolean favorite) {}
    public record LinkedWord(Long wordId,String word) {}
    public record Progress(Long sessionId,String selectionSource,String status,long totalCount,long submissionCount,
        long answerCount,long revealCount,long selfReviewCount,long completedCount,long understoodCount,long partialCount,
        long notUnderstoodCount,Long nextItemId,LocalDateTime createdAt,LocalDateTime completedAt) {}
    public record Current(Long itemId,Long sentenceId,String status,String englishText,Integer difficulty,String source,
        String sourceReference,List<LinkedWord> linkedWords,Long attemptId,String action,String translation,
        String referenceTranslation,String explanation,String selfAssessment,LocalDateTime submittedAt) {}
    public record Feedback(Long attemptId,Long itemId,Long sentenceId,String action,String translation,String referenceTranslation,
        String explanation,String selfAssessment,LocalDate learningDate,LocalDateTime submittedAt,LocalDate reviewDate,
        LocalDateTime reviewedAt,Progress progress,SentenceLearningVO.Schedule reviewSchedule) {}
    public record History(Long attemptId,Long sessionId,Long itemId,Long sentenceId,String englishText,String action,
        String translation,String referenceTranslation,String explanation,String selfAssessment,
        LocalDate learningDate,LocalDateTime submittedAt,LocalDate reviewDate,LocalDateTime reviewedAt) {}
    public record ReviewWord(Long sentenceId,String englishText,Integer difficulty,boolean available,
        Long attemptId,String selfAssessment,LocalDateTime reviewedAt) {}
    public record Favorite(Long sentenceId,boolean favorite) {}
}
