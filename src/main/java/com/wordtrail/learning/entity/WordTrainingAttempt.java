package com.wordtrail.learning.entity;

import com.mybatisflex.annotation.*;
import java.time.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Table("word_training_attempt")
public class WordTrainingAttempt {
    @Id(keyType=KeyType.Auto) private Long id;
    private Long userId;
    private Long wordId;
    private Long batchId;
    private Long batchItemId;
    private String requestId;
    private String presentationId;
    private Long modeVersion;
    private String mode;
    private String action;
    private String inputText;
    private Integer selfAssessment;
    private String verdict;
    private Boolean hintUsed;
    private Boolean extraTraining;
    private Integer attemptNo;
    private Long reviewLogId;
    private LocalDate learningDate;
    private LocalDateTime answeredAt;
}
