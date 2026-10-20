package com.wordtrail.learning.service;

import com.wordtrail.learning.dto.LearningSettingsRequest;
import com.wordtrail.learning.entity.UserLearningSetting;
import com.wordtrail.learning.mapper.UserLearningSettingMapper;
import com.wordtrail.learning.vo.LearningSettingsVO;
import com.wordtrail.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningSettingsService {
    public static final int DEFAULT_DAILY_TARGET = 20;
    private final UserLearningSettingMapper settings;
    private final CurrentUser currentUser;
    private final LearningContext context;

    @Transactional(readOnly = true)
    public LearningSettingsVO get() {
        return new LearningSettingsVO(dailyTarget(currentUser.id()));
    }

    // Only called inside the caller's transaction, with a JWT-derived user ID.
    int dailyTarget(long userId) {
        var existing = settings.selectOneById(userId);
        return existing == null ? DEFAULT_DAILY_TARGET : existing.getDailyTarget();
    }

    @Transactional
    public LearningSettingsVO update(LearningSettingsRequest request) {
        long userId = currentUser.id();
        // Share the task creation lock, including when this user has no settings row yet.
        context.lockUser(userId);
        var existing = settings.selectOneById(userId);
        if (existing == null) {
            var setting = new UserLearningSetting();
            setting.setUserId(userId);
            setting.setDailyTarget(request.dailyTarget());
            setting.setCreatedAt(context.now());
            setting.setUpdatedAt(setting.getCreatedAt());
            if (settings.insertSelective(setting) != 1) { throw new IllegalStateException("Could not create learning settings"); }
        } else if (!existing.getDailyTarget().equals(request.dailyTarget())) {
            var patch = new UserLearningSetting();
            patch.setUserId(userId);
            patch.setDailyTarget(request.dailyTarget());
            patch.setUpdatedAt(context.now());
            if (settings.update(patch) != 1) { throw new IllegalStateException("Could not update learning settings"); }
        }
        return new LearningSettingsVO(request.dailyTarget());
    }
}
