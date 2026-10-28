package com.wordtrail.learning.service;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DictationPolicy {
    private DictationPolicy() {}
    public static final int MAX_ATTEMPTS=1;
    public record Outcome(String verdict,boolean hadFailure,int correctStreak,String completionReason) {}
    public static Outcome answer(String target,String input,boolean reveal) {
        boolean correct=!reveal && target.strip().toLowerCase(Locale.ROOT).equals(input.strip().toLowerCase(Locale.ROOT));
        // Existing storage reasons remain valid: the single allowed attempt is now exhausted.
        return new Outcome(reveal?"REVEALED":correct?"CORRECT":"INCORRECT",!correct,correct?1:0,
            correct?"RECALLED":"LIMIT_REACHED");
    }
    public static String prompt(String translation,String target) {
        return Pattern.compile("(?<![\\p{IsLatin}\\p{N}_])"+Pattern.quote(target)+"(?![\\p{IsLatin}\\p{N}_])",
            Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(translation).replaceAll("[目标词]");
    }
}
