package com.wordtrail.learning.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class ConsolidationQuery {
    public enum Reason { SPELLING_ERROR, ANSWER_REVEALED, UNRECOGNIZED, ATTEMPT_LIMIT, PENDING_VERIFICATION }
    public enum Category { ERROR, VERIFICATION, ALL }
    @Min(1) @NotNull private Integer page=1;
    @Min(1) @Max(100) @NotNull private Integer size=20;
    private Reason reason;
    private Category category;
    public String scope() { return category==null?(reason==Reason.PENDING_VERIFICATION?"VERIFICATION":"ERROR"):category.name(); }
}
