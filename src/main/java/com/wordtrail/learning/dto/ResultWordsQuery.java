package com.wordtrail.learning.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResultWordsQuery {
    @NotNull @Min(value = 1, message = "page 必须大于等于 1")
    private Integer page = 1;
    @NotNull @Min(value = 1, message = "size 必须在 1～100 之间")
    @Max(value = 100, message = "size 必须在 1～100 之间")
    private Integer size = 20;
    @Min(value = 1, message = "result 只能为 1、2、3")
    @Max(value = 3, message = "result 只能为 1、2、3")
    private Integer result;
}
