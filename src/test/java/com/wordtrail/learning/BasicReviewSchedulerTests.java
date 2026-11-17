package com.wordtrail.learning;

import com.wordtrail.learning.service.BasicReviewScheduler;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BasicReviewSchedulerTests {
    private final BasicReviewScheduler scheduler = new BasicReviewScheduler();
    private final LocalDate date = LocalDate.of(2026, 11, 17);

    @Test void schedulesEveryExistingLevelForEachFeedback() {
        int[] days = {1, 3, 7, 15, 30, 60, 90};
        int[] fuzzyLevels = {0, 0, 1, 2, 3, 4, 4};
        for (int previous = 0; previous < days.length; previous++) {
            for (int result = 1; result <= 3; result++) {
                int expected = result == 1 ? Math.min(previous + 1, 6) : result == 2 ? fuzzyLevels[previous] : 0;
                var scheduled = scheduler.schedule(previous, true, result, date);
                assertEquals(expected, scheduled.reviewLevel(), "level " + previous + ", result " + result);
                assertEquals(date.plusDays(days[expected]).atStartOfDay(), scheduled.nextReviewAt());
            }
        }
    }

    @Test void firstLearningAlwaysSchedulesTomorrowAndRejectsInvalidInput() {
        for (int result = 1; result <= 3; result++) {
            var scheduled = scheduler.schedule(6, false, result, date);
            assertEquals(0, scheduled.reviewLevel());
            assertEquals(date.plusDays(1).atStartOfDay(), scheduled.nextReviewAt());
        }
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(-1, true, 1, date));
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(7, true, 1, date));
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(0, true, 0, date));
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(0, true, 4, date));
    }
}
