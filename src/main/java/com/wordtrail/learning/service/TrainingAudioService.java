package com.wordtrail.learning.service;

import com.wordtrail.learning.dto.PronunciationRequest.Accent;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.word.mapper.WordMapper;
import com.wordtrail.learning.mapper.LearningBatchItemMapper;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.wordtrail.learning.service.LearningContext.conflict;

@Service @RequiredArgsConstructor
public class TrainingAudioService {
    private final PronunciationProvider provider;
    private final LearningContext context;
    private final CurrentUser currentUser;
    private final WordMapper words;
    private final LearningBatchItemMapper items;
    private final LearningSettingsService settings;
    private final DailyLearningService daily;

    @Transactional(readOnly=true)
    public String trainingTarget(long batchId,long itemId,String presentationId,long version) {
        var batch=context.ownedBatch(currentUser.id(),batchId,false);
        if(!"DAILY".equals(batch.getTaskType()) && !"CONSOLIDATION".equals(batch.getPracticeMode()) && !"FOLLOW_TYPING".equals(batch.getPracticeMode())) throw conflict("NOT_TRAINING_TASK","不是统一训练任务");
        if("DAILY".equals(batch.getTaskType()) && !context.today().equals(batch.getLearningDate())) throw conflict("DAILY_TASK_EXPIRED","请进入今日任务");
        var item=items.selectOneById(itemId);
        if(item==null || item.getBatchId()!=batchId || !Objects.equals(item.getStudyPresentationId(),presentationId)
                || !Objects.equals(item.getStudyPresentationVersion(),version) || batch.getModeVersion()!=version
                || "ABANDONED".equals(batch.getStatus())) throw conflict("STALE_TRAINING_ANSWER","音频展示已失效，请重新取题");
        if("DAILY".equals(batch.getTaskType()) || "FOLLOW_TYPING".equals(batch.getPracticeMode())) daily.availableState(currentUser.id(),item);
        if(item.getStudyWord()==null) throw conflict("TRAINING_WORD_UNAVAILABLE","尚未生成题目快照");
        return item.getStudyWord();
    }
    @Transactional(readOnly=true) public String wordTarget(long wordId) {
        var word=words.selectOneById(wordId); if(word==null) throw conflict("WORD_NOT_FOUND","单词不存在"); return word.getWord();
    }
    public byte[] audio(String word,Accent accent) {
        if(word==null || word.isBlank()) throw conflict("TRAINING_WORD_UNAVAILABLE","单词拼写为空");
        Accent selected=accent==null?Accent.valueOf(settings.pronunciation(currentUser.id()).accent()):accent;
        return provider.audio(word.strip(),selected);
    }
}
