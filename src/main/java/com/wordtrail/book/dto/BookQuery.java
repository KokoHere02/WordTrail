package com.wordtrail.book.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BookQuery {
    @NotNull @Min(value = 1, message = "page 必须大于等于 1")
    private Integer page = 1;
    @NotNull @Min(value = 1, message = "size 必须在 1～100 之间")
    @Max(value = 100, message = "size 必须在 1～100 之间")
    private Integer size = 20;
    private String keyword;
}
