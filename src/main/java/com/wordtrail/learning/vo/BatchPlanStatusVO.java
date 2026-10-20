package com.wordtrail.learning.vo;

import com.wordtrail.learning.dto.PlanStatus;

public record BatchPlanStatusVO(int requestedCount, int changedCount, int unchangedCount, PlanStatus status) {}
