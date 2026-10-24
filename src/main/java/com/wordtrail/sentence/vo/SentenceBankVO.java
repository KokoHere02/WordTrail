package com.wordtrail.sentence.vo;

import java.util.List;
import com.wordtrail.sentence.vo.SentenceTranslationVO.LinkedWord;

public final class SentenceBankVO {
    private SentenceBankVO() {}
    public record Access(boolean canManage) {}
    public record ManagedQuestion(Long sentenceId,String englishText,String referenceTranslation,String explanation,
            Integer difficulty,String source,String sourceReference,String status,Long version,List<LinkedWord> linkedWords) {}
    public record ImportRow(int rowNumber,String status,String message,Long existingSentenceId,
            String englishText,String referenceTranslation,String explanation,Integer difficulty,String source,
            String sourceReference,List<Long> wordIds) {}
    public record Preview(int totalCount,long readyCount,long duplicateCount,long invalidCount,List<ImportRow> rows) {}
    public record Imported(Long importId,int importedCount,List<Long> sentenceIds) {}
}
