package com.wordtrail.learning.service;

import com.wordtrail.learning.dto.WeakWordsQuery;
import com.wordtrail.learning.mapper.ReviewEvidenceMapper;
import com.wordtrail.learning.vo.WeakWordsVO;
import com.wordtrail.learning.vo.WeakWordVO;
import com.wordtrail.security.CurrentUser;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class WeakWordService {
    private final CurrentUser currentUser;
    private final ReviewEvidenceMapper evidence;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WeakWordsVO list(WeakWordsQuery query) {
        long userId = currentUser.id(); String source = query.getSource().name();
        var coverage = evidence.coverage(userId, source, null);
        long offset = ((long) query.getPage()-1)*query.getSize();
        var records = offset >= coverage.weakCount() ? List.<WeakWordVO>of()
            : evidence.weakWords(userId, source, null, offset, query.getSize());
        return new WeakWordsVO(query.getPage(), query.getSize(), coverage.weakCount(), records,
            source, "RECORDED_EVENTS", coverage.incompleteWordCount()==0, coverage.incompleteWordCount(),
            coverage.firstRecordedAt(), coverage.lastRecordedAt());
    }
}
