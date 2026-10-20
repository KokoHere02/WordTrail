package com.wordtrail.learning.vo;

import java.util.List;

public record LearningCalendarVO(String month, boolean statisticsComplete, List<CalendarDayVO> days) {}
