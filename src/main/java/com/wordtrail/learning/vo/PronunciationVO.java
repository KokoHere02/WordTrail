package com.wordtrail.learning.vo;

import java.math.BigDecimal;
public record PronunciationVO(boolean autoPlay,String accent,BigDecimal playbackRate,int repeatCount) {}
