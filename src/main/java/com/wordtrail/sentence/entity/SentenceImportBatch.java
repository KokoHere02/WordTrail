package com.wordtrail.sentence.entity;
import com.mybatisflex.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Table("sentence_import_batch")
public class SentenceImportBatch {
    @Id(keyType=KeyType.Auto) private Long id;
    private Long userId;
    private String requestId;
    private String contentHash;
    private Integer rowCount;
    private LocalDateTime createdAt;
}
