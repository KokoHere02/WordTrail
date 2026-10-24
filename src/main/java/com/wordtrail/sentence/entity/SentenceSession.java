package com.wordtrail.sentence.entity;

import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Table("sentence_translation_session")
public class SentenceSession {
    @Id(keyType=KeyType.Auto) private Long id;
    private Long userId;
    private String selectionSource;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
