package com.wordtrail.learning.service;

import com.wordtrail.learning.dto.LearningSettingsRequest;
import com.wordtrail.learning.dto.DailyAllocationRequest;
import com.wordtrail.learning.dto.PronunciationRequest;
import com.wordtrail.learning.entity.UserLearningSetting;
import com.wordtrail.learning.mapper.UserLearningSettingMapper;
import com.wordtrail.learning.vo.LearningSettingsVO;
import com.wordtrail.learning.vo.PronunciationVO;
import java.math.BigDecimal;
import com.mybatisflex.core.query.QueryWrapper;
import static com.mybatisflex.core.query.QueryMethods.column;
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
        return view(settings.selectOneById(currentUser.id()));
    }

    // Only called inside the caller's transaction, with a JWT-derived user ID.
    int dailyTarget(long userId) {
        var existing = settings.selectOneById(userId);
        return existing == null ? DEFAULT_DAILY_TARGET : existing.getDailyTarget();
    }

    String preferredMode(long userId) {
        var existing=settings.selectOneById(userId);
        return dailyMode(existing);
    }
    UserLearningSetting allocation(long userId) { return settings.selectOneById(userId); }
    public PronunciationVO pronunciation(long userId) { return view(settings.selectOneById(userId)).pronunciation(); }
    private LearningSettingsVO view(UserLearningSetting value) {
        return new LearningSettingsVO(value==null?DEFAULT_DAILY_TARGET:value.getDailyTarget(),
            dailyMode(value),value==null?null:value.getNewWordTarget(),value==null?null:value.getReviewLimit(),
            new PronunciationVO(value!=null && Boolean.TRUE.equals(value.getAudioAutoPlay()),
                value==null?"US":value.getAudioAccent(),value==null?BigDecimal.ONE:value.getAudioPlaybackRate(),value==null?1:value.getAudioRepeatCount()));
    }
    private String dailyMode(UserLearningSetting value) {
        return value==null || "FOLLOW_TYPING".equals(value.getPreferredStudyMode())?"RECOGNITION":value.getPreferredStudyMode();
    }
    private UserLearningSetting editable(long userId) {
        var value=settings.selectOneById(userId);
        if(value==null) {
            value=new UserLearningSetting(); value.setUserId(userId); value.setDailyTarget(DEFAULT_DAILY_TARGET);
            value.setPreferredStudyMode("RECOGNITION"); value.setAudioAutoPlay(false); value.setAudioAccent("US");
            value.setAudioPlaybackRate(BigDecimal.ONE); value.setAudioRepeatCount(1); value.setCreatedAt(context.now());
            value.setUpdatedAt(value.getCreatedAt());
            if(settings.insertSelective(value)!=1) throw new IllegalStateException("Could not initialize settings");
        }
        return value;
    }
    @Transactional public LearningSettingsVO allocation(DailyAllocationRequest request) {
        long userId=currentUser.id(); context.lockUser(userId); var value=editable(userId);
        value.setNewWordTarget(request.newWordTarget()); value.setReviewLimit(request.reviewLimit());
        value.setDailyTarget(request.newWordTarget()+request.reviewLimit()); value.setUpdatedAt(context.now());
        if(settings.update(value)!=1) throw new IllegalStateException("Could not update daily allocation");
        return view(value);
    }
    @Transactional public LearningSettingsVO pronunciation(PronunciationRequest request) {
        long userId=currentUser.id(); context.lockUser(userId); var value=editable(userId);
        value.setAudioAutoPlay(request.autoPlay()); value.setAudioAccent(request.accent().name());
        value.setAudioPlaybackRate(request.playbackRate()); value.setAudioRepeatCount(request.repeatCount()); value.setUpdatedAt(context.now());
        if(settings.update(value)!=1) throw new IllegalStateException("Could not update pronunciation settings");
        return view(value);
    }

    // Caller owns the user write lock, including creation when settings do not exist.
    void rememberMode(long userId, String mode) {
        var existing=settings.selectOneById(userId);
        if(existing==null) {
            existing=new UserLearningSetting(); existing.setUserId(userId);
            existing.setDailyTarget(DEFAULT_DAILY_TARGET); existing.setCreatedAt(context.now());
            existing.setUpdatedAt(context.now()); existing.setPreferredStudyMode(mode);
            if(settings.insertSelective(existing)!=1) throw new IllegalStateException("Could not save study preference");
        } else {
            existing.setPreferredStudyMode(mode); existing.setUpdatedAt(context.now());
            if(settings.update(existing)!=1) throw new IllegalStateException("Could not save study preference");
        }
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
        } else if (!existing.getDailyTarget().equals(request.dailyTarget()) || existing.getNewWordTarget()!=null) {
            existing.setDailyTarget(request.dailyTarget()); existing.setNewWordTarget(null); existing.setReviewLimit(null);
            existing.setUpdatedAt(context.now());
            if (settings.updateByQuery(existing,false,QueryWrapper.create().where(column("user_id").eq(userId))) != 1) { throw new IllegalStateException("Could not update learning settings"); }
        }
        return view(settings.selectOneById(userId));
    }
}
