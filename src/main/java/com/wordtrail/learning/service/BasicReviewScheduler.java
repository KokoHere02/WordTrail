package com.wordtrail.learning.service;

import java.time.*;
import org.springframework.stereotype.Component;

@Component
public class BasicReviewScheduler implements ReviewScheduler {
    private static final int[] DAYS = {1, 3, 7, 15, 30, 60, 90};

    @Override public Schedule schedule(int previousLevel, boolean previouslyStarted, int result, LocalDate date) {
        if (result < 1 || result > 3 || previousLevel < 0 || previousLevel > 6) {
            throw new IllegalArgumentException("Invalid review feedback or level");
        }
        int level = result != 1 || !previouslyStarted ? 0 : Math.min(previousLevel + 1, DAYS.length - 1);
        return new Schedule(level, date.plusDays(DAYS[level]).atStartOfDay());
    }
}
