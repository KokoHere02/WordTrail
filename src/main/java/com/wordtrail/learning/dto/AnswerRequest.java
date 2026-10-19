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
    @Pattern(regexp = "[A-Za-z0-9_-]{8,64}", message = "requestId 必须为 8～64 位字母、数字、下划线或横线")
    private String requestId;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("不支持的请求字段");
    }
}
