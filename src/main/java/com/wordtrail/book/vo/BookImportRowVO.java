package com.wordtrail.book.vo;

import com.wordtrail.word.vo.DictionaryWordVO;
import java.util.List;

public record BookImportRowVO(int rowNumber, String input, String word, String status,
        Integer duplicateOfRow, String message, long candidateCount, boolean hasMoreCandidates,
        List<DictionaryWordVO> candidates) {}
