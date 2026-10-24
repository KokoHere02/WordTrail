package com.wordtrail.sentence.vo;
import com.wordtrail.sentence.dto.SentenceNoteRequest.ErrorTag;
import java.time.LocalDateTime;
import java.util.List;
public final class SentenceNoteVO {
    private SentenceNoteVO() {}
    public record Note(String content,List<ErrorTag> errorTags,Long attemptId,long version,LocalDateTime updatedAt) {}
    public record Entry(Long sentenceId,String englishText,boolean available,Note note) {}
}
