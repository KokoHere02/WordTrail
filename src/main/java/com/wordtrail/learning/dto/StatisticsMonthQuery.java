package com.wordtrail.learning.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StatisticsMonthQuery {
    @Pattern(regexp = "[1-9][0-9]{3}-[0-9]{2}", message = "month 必须为 YYYY-MM 格式")
    private String month;
}
