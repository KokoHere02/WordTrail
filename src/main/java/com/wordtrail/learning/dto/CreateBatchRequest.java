package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateBatchRequest {
    @NotNull(message = "bookId 不能为空") @Positive(message = "bookId 必须为正数")
    private Long bookId;
    @NotNull(message = "selectionType 不能为空")
    private SelectionType selectionType;
    // Do not initialize this field: MANUAL must distinguish an omitted count.
    @Min(value = 1, message = "count 必须在 1～100 之间")
    @Max(value = 100, message = "count 必须在 1～100 之间")
    private Integer count;
    private List<@NotNull(message = "wordIds 不能包含 null")
        @Positive(message = "wordIds 必须为正数") Long> wordIds;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        // In particular, user_id / userId cannot be supplied by the caller.
        throw new IllegalArgumentException("不支持的请求字段");
    }
}
