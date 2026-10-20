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
    @Size(max = 255, message = "keyword 最长 255 个字符")
    private String keyword;
    @NotNull @Pattern(regexp = "joined_at|next_review_at", message = "sortBy 只能为 joined_at 或 next_review_at")
    private String sortBy = "joined_at";
    @NotNull private PlanSortDirection direction = PlanSortDirection.DESC;
}
