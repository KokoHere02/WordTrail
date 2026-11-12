package com.wordtrail.learning;

import com.wordtrail.learning.service.DictationPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DictationPatternTests {
    @Test void masksLettersAndNumbersWhileKeepingPunctuation() {
        assertEquals("___-_'_ ___", DictationPolicy.answerPattern("cat-a's dog"));
        assertEquals("___", DictationPolicy.answerPattern("a2b"));
    }
}
