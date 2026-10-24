package com.wordtrail;

import com.wordtrail.learning.service.DictationPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DictationPolicyTests {
    @Test void gradingTrimsEndsIgnoresCaseAndPreservesMeaningfulSpelling() {
        assertEquals("CORRECT",DictationPolicy.answer("Apple"," APPLE ",false,false,0,1).verdict());
        for(String input:new String[]{"takeoff","take  off","takes off","take-off"})
            assertEquals("INCORRECT",DictationPolicy.answer("take off",input,false,false,0,1).verdict());
        assertEquals("INCORRECT",DictationPolicy.answer("can't","cant",false,false,0,1).verdict());
        assertEquals("INCORRECT",DictationPolicy.answer("café","cafe",false,false,0,1).verdict());
    }
    @Test void priorFailureRequiresTwoConsecutiveCorrectAttemptsAndRevealsNeverPass() {
        var wrong=DictationPolicy.answer("apple","aple",false,false,0,1); assertFalse(wrong.done());
        var first=DictationPolicy.answer("apple","apple",false,true,0,2); assertFalse(first.done());
        var second=DictationPolicy.answer("apple","apple",false,true,1,3); assertTrue(second.done());
        assertEquals("RECALLED",second.completionReason());
        var reveal=DictationPolicy.answer("apple",null,true,true,1,4); assertEquals(0,reveal.correctStreak()); assertFalse(reveal.done());
    }
    @Test void finalAttemptCanEndWithoutPassingEvenIfLastAnswerIsCorrect() {
        var last=DictationPolicy.answer("apple","apple",false,true,0,6);
        assertTrue(last.done()); assertEquals("LIMIT_REACHED",last.completionReason()); assertEquals("CORRECT",last.verdict());
        assertEquals("RECALLED",DictationPolicy.answer("apple","apple",false,true,1,6).completionReason());
    }
    @Test void promptMasksStandaloneTargetsIncludingAdjacentChineseButKeepsOriginalDefinition() {
        String translation="n. state 状态，STATE的用法，stateful例子";
        assertEquals("n. [目标词] 状态，[目标词]的用法，stateful例子",DictationPolicy.prompt(translation,"state"));
        assertEquals("n. state 状态，STATE的用法，stateful例子",translation);
    }
}
