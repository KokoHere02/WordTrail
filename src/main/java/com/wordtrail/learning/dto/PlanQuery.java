package com.wordtrail.learning.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class PlanQuery {
    @NotNull @Min(1) private Integer page = 1;
    @NotNull @Min(1) @Max(100) private Integer size = 20;
    @NotNull private PlanStatus status = PlanStatus.ACTIVE;
    @NotNull private PlanLearningStatus learningStatus = PlanLearningStatus.ALL;
}
