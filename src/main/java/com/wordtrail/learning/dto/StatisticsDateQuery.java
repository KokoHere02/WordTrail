package com.wordtrail.learning.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StatisticsDateQuery {
    @Pattern(regexp = "[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}", message = "date 必须为 YYYY-MM-DD 格式")
    private String date;
}
