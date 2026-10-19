package com.wordtrail.learning.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnswerRequest {
    @NotNull(message = "result 不能为空")
    @Min(value = 1, message = "result 只能为 1、2、3")
    @Max(value = 3, message = "result 只能为 1、2、3")
    private Integer result;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("不支持的请求字段");
    }
}
