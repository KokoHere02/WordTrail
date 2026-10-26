package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CreateConsolidationRequest {
    @NotNull @Pattern(regexp="[A-Za-z0-9_-]{8,64}") private String requestId;
    private ConsolidationQuery.Reason reason;
    private ConsolidationQuery.Category category;
    @NotNull private StudyMode mode=StudyMode.DICTATION;
    @Min(1) @Max(100) private Integer count;
    @Size(min=1,max=100) private List<@NotNull @Positive Long> wordIds;
    public String scope() { return category==null?(reason==ConsolidationQuery.Reason.PENDING_VERIFICATION?"VERIFICATION":"ERROR"):category.name(); }
    @AssertTrue(message="wordIds 和 count 不能同时提供") public boolean isValidSelection() { return wordIds==null || count==null; }
    @JsonAnySetter public void reject(String key,Object value) { throw new IllegalArgumentException("不支持的请求字段"); }
}
