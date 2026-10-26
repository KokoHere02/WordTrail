package com.wordtrail.learning.vo;
import java.time.LocalDate;
public record TodayVO(LocalDate learningDate, int dailyTarget, long activeCount,
                      long dueCount, long newCount, long unassignedCount, boolean canAddMore, DailyTaskVO task,
                      long reviewBacklogCount,long overdueReviewCount,Integer newWordTarget,Integer reviewLimit) {}
