package com.wordtrail.book.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class RandomBookWordsQuery {
    @NotNull @Min(1) @Max(100) private Integer count = 100;
}
