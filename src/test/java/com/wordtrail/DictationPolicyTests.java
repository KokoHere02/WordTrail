package com.wordtrail;

import com.wordtrail.learning.service.DictationPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DictationPolicyTests {
    @Test void gradingTrimsEndsIgnoresCaseAndPreservesMeaningfulSpelling() {
        assertEquals("CORRECT",DictationPolicy.answer("Apple"," APPLE ",false).verdict());
        for(String input:new String[]{"takeoff","take  off","takes off","take-off"})
            assertEquals("INCORRECT",DictationPolicy.answer("take off",input,false).verdict());
        assertEquals("INCORRECT",DictationPolicy.answer("can't","cant",false).verdict());
        assertEquals("INCORRECT",DictationPolicy.answer("café","cafe",false).verdict());
    }
    @Test void oneIncorrectAnswerExhaustsTheQuestionAndCorrectAnswersPassImmediately() {
        assertEquals(1,DictationPolicy.MAX_ATTEMPTS);
        var wrong=DictationPolicy.answer("apple","aple",false);
        assertTrue(wrong.hadFailure()); assertEquals(0,wrong.correctStreak());
        assertEquals("LIMIT_REACHED",wrong.completionReason());
        var correct=DictationPolicy.answer("apple","apple",false);
        assertFalse(correct.hadFailure()); assertEquals(1,correct.correctStreak());
        assertEquals("RECALLED",correct.completionReason());
    }
    @Test void revealingConsumesTheOnlyAttemptAndNeverPasses() {
        var reveal=DictationPolicy.answer("apple",null,true);
        assertEquals("REVEALED",reveal.verdict()); assertTrue(reveal.hadFailure());
        assertEquals(0,reveal.correctStreak()); assertEquals("LIMIT_REACHED",reveal.completionReason());
    }
    @Test void promptMasksStandaloneTargetsIncludingAdjacentChineseButKeepsOriginalDefinition() {
        String translation="n. state 状态，STATE的用法，stateful例子";
        assertEquals("n. [目标词] 状态，[目标词]的用法，stateful例子",DictationPolicy.prompt(translation,"state"));
        assertEquals("n. state 状态，STATE的用法，stateful例子",translation);
    }
}
