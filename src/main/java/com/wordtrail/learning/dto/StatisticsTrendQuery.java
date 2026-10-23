package com.wordtrail.learning.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class StatisticsTrendQuery extends StatisticsSourceQuery {
    @NotNull private Integer days = 7;

    @AssertTrue(message = "days 只能为 7 或 30")
    public boolean isSupportedWindow() { return days == null || days == 7 || days == 30; }
}
