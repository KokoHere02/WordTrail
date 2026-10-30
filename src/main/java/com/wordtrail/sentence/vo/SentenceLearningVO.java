package com.wordtrail.sentence.vo;
import java.time.*;
import java.util.List;
public final class SentenceLearningVO {
    private SentenceLearningVO() {}
    public record Schedule(Long sentenceId,Long lastAttemptId,int reviewLevel,String lastAssessment,LocalDateTime reviewedAt,LocalDate nextReviewDate) {}
    public record DueQuestion(Long sentenceId,String englishText,Integer difficulty,String source,String sourceReference,
            int reviewLevel,String lastAssessment,LocalDate nextReviewDate) {}
    public record Totals(long submissionCount,long answerCount,long revealCount,long selfReviewCount) {}
    public record Day(LocalDate learningDate,long submissionCount,long answerCount,long revealCount,long selfReviewCount) {}
    public record Statistics(long submissionCount,long answerCount,long revealCount,long selfReviewCount,
            long weakSentenceCount,long dueSentenceCount,long learningDayCount,LocalDate from,LocalDate to,List<Day> days,
            long currentStreak,long longestStreak) {}
    public record ActivityDay(LocalDate date,boolean hasActivity,long submissionCount,long answerCount,
            long revealCount,long selfReviewCount) {}
    public record Calendar(String month,List<ActivityDay> days) {}
    public record ActivityDetail(Long attemptId,Long sessionId,Long itemId,Long sentenceId,String englishText,
            String action,String translation,String referenceTranslation,String explanation,String selfAssessment,
            LocalDate learningDate,LocalDateTime submittedAt,LocalDate reviewDate,LocalDateTime reviewedAt,
            boolean submittedOnDate,boolean reviewedOnDate,LocalDateTime activityAt) {}
}
