package com.wordtrail.learning.service;

import java.time.*;

public interface ReviewScheduler {
    record Schedule(int reviewLevel, LocalDateTime nextReviewAt) {}
    Schedule schedule(int previousLevel, boolean previouslyStarted, int result, LocalDate learningDate);
}
