package com.wordtrail.learning.service;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DictationPolicy {
    private DictationPolicy() {}
    public static final int MAX_ATTEMPTS=6;
    public record Outcome(String verdict,boolean hadFailure,int correctStreak,boolean done,String completionReason) {}
    public static Outcome answer(String target,String input,boolean reveal,boolean failed,int streak,int attempt) {
        boolean correct=!reveal && target.strip().toLowerCase(Locale.ROOT).equals(input.strip().toLowerCase(Locale.ROOT));
        failed=failed || !correct; streak=correct?streak+1:0;
        boolean passed=correct && (!failed || streak>=2);
        boolean done=passed || attempt>=MAX_ATTEMPTS;
        return new Outcome(reveal?"REVEALED":correct?"CORRECT":"INCORRECT",failed,streak,done,
            passed?"RECALLED":done?"LIMIT_REACHED":null);
    }
    public static String prompt(String translation,String target) {
        return Pattern.compile("(?<![\\p{IsLatin}\\p{N}_])"+Pattern.quote(target)+"(?![\\p{IsLatin}\\p{N}_])",
            Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(translation).replaceAll("[目标词]");
    }
}
