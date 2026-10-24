package com.wordtrail.book;

import com.wordtrail.book.dto.BookImportFormat;
import com.wordtrail.book.support.BookTransferText;
import com.wordtrail.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookTransferTextTests {
    @Test void txtPreservesPhrasesCaseAndPhysicalRowNumbers() {
        var parsed=BookTransferText.parse("\uFEFF apple \r\n\r\nTake off\rbanana\n",BookImportFormat.TXT);
        assertEquals(List.of("apple","Take off","banana"),parsed.rows().stream().map(BookTransferText.Row::word).toList());
        assertEquals(List.of(1,3,4),parsed.rows().stream().map(BookTransferText.Row::rowNumber).toList());
        assertEquals(2,parsed.ignoredBlankCount()); assertEquals(" apple ",parsed.rows().get(0).input());
    }

    @Test void csvReadsBomReorderedHeadersQuotedCommasAndMultilineDefinitions() {
        var parsed=BookTransferText.parse("\uFEFFtranslation,word,phonetic\r\n\"line1\r\nline2, \"\"quoted\"\"\",\"take off\",/test/\r\nplain,apple,\r\n",BookImportFormat.CSV);
        assertEquals(List.of("take off","apple"),parsed.rows().stream().map(BookTransferText.Row::word).toList());
        assertEquals(List.of(2,4),parsed.rows().stream().map(BookTransferText.Row::rowNumber).toList());
        assertEquals(List.of("translation","phonetic"),parsed.ignoredColumns());
        assertNull(parsed.rows().get(0).problem());
    }

    @Test void csvReportsMalformedDataRowsAndRetainsEmptyWordsInsteadOfDroppingThem() {
        var parsed=BookTransferText.parse("word,translation\n,meaning\napple\n\"take\noff\",meaning\n",BookImportFormat.CSV);
        assertEquals(3,parsed.rows().size());
        assertEquals("单词不能为空",parsed.rows().get(0).problem());
        assertEquals("列数与 CSV 表头不一致",parsed.rows().get(1).problem());
        assertEquals("单词不能含控制字符或换行",parsed.rows().get(2).problem());
        var empty=BookTransferText.parse("word\n\"\"\n \n",BookImportFormat.CSV);
        assertEquals(1,empty.rows().size()); assertEquals("单词不能为空",empty.rows().get(0).problem());
        assertEquals(1,empty.ignoredBlankCount());
    }

    @Test void csvRejectsMalformedQuotesAndAmbiguousOrMissingWordHeaders() {
        for(String text:List.of("word\n\"unclosed","word\nabc\"quoted","word\n\"apple\"tail",
                "word,单词\na,b","translation\napple"))
            assertEquals("IMPORT_CSV_INVALID",assertThrows(BusinessException.class,()->BookTransferText.parse(text,BookImportFormat.CSV)).code());
    }

    @Test void csvFormulaProtectionRoundTripsApostrophesAndSpecialCells() {
        for(String original:List.of("apple","take,off","a\"b","'tis","'=literal","=CMD()","  +1","-word","@word")) {
            String encoded=BookTransferText.csvCell(original);
            var parsed=BookTransferText.parse("word\r\n"+encoded+"\r\n",BookImportFormat.CSV);
            assertEquals(original.strip(),parsed.rows().get(0).word());
            if(original.stripLeading().matches("[=+\\-@].*")) assertTrue(encoded.startsWith("\"'"));
        }
        assertEquals("\"\"",BookTransferText.csvCell(null));
    }

    @Test void utf8IsStrictAndTextAndRowsAreBounded() {
        assertEquals("\uFEFF单词",BookTransferText.decode("\uFEFF单词".getBytes(StandardCharsets.UTF_8)));
        assertEquals("IMPORT_ENCODING_UNSUPPORTED",assertThrows(BusinessException.class,()->BookTransferText.decode(new byte[]{(byte)0xff,(byte)0xfe})).code());
        assertEquals("IMPORT_TOO_LARGE",assertThrows(BusinessException.class,()->BookTransferText.decode(new byte[262145])).code());
        assertEquals("IMPORT_ROW_LIMIT",assertThrows(BusinessException.class,()->BookTransferText.parse("apple\n".repeat(101),BookImportFormat.TXT)).code());
        assertEquals(100,BookTransferText.parse("apple\n".repeat(100),BookImportFormat.TXT).rows().size());
    }

    @Test void emptyCsvExportIsPreviewableAndInvalidTermsStayVisible() {
        assertEquals(0,BookTransferText.parse("\uFEFFword,phonetic,translation\r\n",BookImportFormat.CSV).rows().size());
        var parsed=BookTransferText.parse("word\n"+"x".repeat(256)+"\n\"tab\tword\"\n",BookImportFormat.CSV);
        assertEquals(2,parsed.rows().size()); assertNotNull(parsed.rows().get(0).problem()); assertNotNull(parsed.rows().get(1).problem());
        assertEquals("IMPORT_EMPTY",assertThrows(BusinessException.class,()->BookTransferText.parse("  ",BookImportFormat.TXT)).code());
    }
}
