package com.wordtrail.learning.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Table("user_learning_setting")
public class UserLearningSetting {
    @Id
    private Long userId;
    private Integer dailyTarget;
    private String preferredStudyMode;
    private Integer newWordTarget;
    private Integer reviewLimit;
    private Boolean audioAutoPlay;
    private String audioAccent;
    private java.math.BigDecimal audioPlaybackRate;
    private Integer audioRepeatCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
