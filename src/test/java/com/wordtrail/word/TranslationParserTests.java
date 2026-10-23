package com.wordtrail.word;

import com.wordtrail.word.support.TranslationParser;
import com.wordtrail.word.vo.TranslationGroupVO;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TranslationParserTests {
    @Test void importedEscapedLinesKeepEverySenseAndDomainNoteInSourceOrder() {
        String original="n. 州, 状态\\na. 国家的\\nvt. 说明, 陈述\\n[计] 状态";
        assertEquals(List.of(new TranslationGroupVO("n.","州, 状态"),new TranslationGroupVO("a.","国家的"),
            new TranslationGroupVO("vt.","说明, 陈述"),new TranslationGroupVO(null,"[计] 状态")),TranslationParser.parse(original));
        assertTrue(original.contains("\\n"));
    }

    @Test void sameLinePartsSplitWithoutSplittingCommasSemicolonsOrOrdinaryAbbreviations() {
        assertEquals(List.of(new TranslationGroupVO("n.","记录；记载, 例 e.g. U.S.A. 3.14"),new TranslationGroupVO("v.","记录；录制")),
            TranslationParser.parse("n. 记录；记载, 例 e.g. U.S.A. 3.14 v. 记录；录制"));
        assertEquals(List.of(new TranslationGroupVO(null,"e.g. U.S.A. v2.5 www.example.com")),
            TranslationParser.parse("e.g. U.S.A. v2.5 www.example.com"));
    }

    @Test void actualAndEscapedLineEndingsSharePresentationWithoutGeneralUnescaping() {
        var expected=List.of(new TranslationGroupVO("n.","记录"),new TranslationGroupVO("vt.","录制"));
        for(String ending:List.of("\n","\r\n","\r","\\n","\\r\\n","\\r"))
            assertEquals(expected,TranslationParser.parse("n. 记录"+ending+"vt. 录制"));
        assertEquals(List.of(new TranslationGroupVO(null,"[计] C:\\temp\\data <b>原文</b>")),
            TranslationParser.parse("[计] C:\\temp\\data <b>原文</b>"));
    }

    @Test void unclassifiedAndUnknownLabelsAreKeptWithoutInventingPartsOfSpeech() {
        assertEquals(List.of(new TranslationGroupVO(null,"普通释义"),new TranslationGroupVO(null,"xyz. 未知标识"),
            new TranslationGroupVO("n.","名词释义"),new TranslationGroupVO(null,"[医] 补充说明")),
            TranslationParser.parse("普通释义\\nxyz. 未知标识\\nn. 名词释义\\n[医] 补充说明"));
    }

    @Test void jointLabelsShareOneMeaningAndRepeatedLabelsDoNotReorderOrMergeSenses() {
        assertEquals(List.of(new TranslationGroupVO("vt. & vi.","记录"),new TranslationGroupVO("N.","记录本"),
            new TranslationGroupVO("n./v.","共同释义"),new TranslationGroupVO("n.","另外一义")),
            TranslationParser.parse("vt. & vi. 记录\nN. 记录本\nn./v. 共同释义\nn. 另外一义"));
    }

    @Test void danglingLabelsAndUnmarkedPrefixesAreNeverDropped() {
        assertEquals(List.of(new TranslationGroupVO(null,"前置说明"),new TranslationGroupVO("n.","名词"),
            new TranslationGroupVO(null,"vt.")),TranslationParser.parse("前置说明 n. 名词\nvt."));
        assertEquals(List.of(new TranslationGroupVO(null,"n."),new TranslationGroupVO("v.","动词")),
            TranslationParser.parse("n. v. 动词"));
    }

    @Test void nullAndBlankTranslationsReturnEmptyImmutableGroups() {
        for(String value:new String[]{null,""," \t\r\n","\\n\\n"}) assertEquals(List.of(),TranslationParser.parse(value));
        assertThrows(UnsupportedOperationException.class,()->TranslationParser.parse("n. 记录").add(new TranslationGroupVO(null,"x")));
    }
}
