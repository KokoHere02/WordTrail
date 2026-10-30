package com.wordtrail.learning.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class LearningRecordQuery {
    public enum Type { ALL, DAILY, DICTATION, SENTENCE }
    public enum Status { ALL, IN_PROGRESS, COMPLETED, INCOMPLETE, ABANDONED }

    @NotNull private Type type = Type.ALL;
    @NotNull private Status status = Status.ALL;
    @NotNull @Min(1) private Integer page = 1;
    @NotNull @Min(1) @Max(100) private Integer size = 20;
}
