package com.wordtrail.learning.service;

import org.springframework.stereotype.Component;

/** Transparent intraday consolidation rules, separate from cross-day scheduling. */
@Component
public class DailyReinforcementPolicy {
    public static final int MAX_ANSWERS = 6;

    public record Outcome(int remaining, boolean done, String completionReason, int otherWords) {}

    public Outcome feedback(int remainingBefore, int attempt, int result) {
        int remaining = result == 1 ? Math.max(0, remainingBefore - 1)
            : Math.max(remainingBefore, result == 2 ? 2 : 3);
        boolean recalled = remaining == 0;
        boolean done = recalled || attempt >= MAX_ANSWERS;
        return new Outcome(remaining, done, done ? (recalled ? "RECALLED" : "LIMIT_REACHED") : null,
            result == 1 ? 3 + (3 - Math.min(remaining, 3)) * 2 : 3);
    }
}
