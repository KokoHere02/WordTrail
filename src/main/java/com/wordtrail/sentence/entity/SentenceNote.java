package com.wordtrail.sentence.entity;
import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Table("user_sentence_note")
public class SentenceNote {
    @Id(keyType=KeyType.Auto) private Long id;
    private Long userId;
    private Long sentenceId;
    private String content;
    private Long attemptId;
    private Long version;
    private LocalDateTime updatedAt;
}
