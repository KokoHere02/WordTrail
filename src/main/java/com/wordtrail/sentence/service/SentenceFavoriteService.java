package com.wordtrail.sentence.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.wordtrail.common.exception.BusinessException;
import com.wordtrail.learning.service.LearningContext;
import com.wordtrail.security.CurrentUser;
import com.wordtrail.sentence.entity.SentenceFavorite;
import com.wordtrail.sentence.mapper.SentenceFavoriteMapper;
import com.wordtrail.sentence.mapper.SentenceQuestionMapper;
import com.wordtrail.sentence.vo.SentenceTranslationVO.Favorite;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.mybatisflex.core.query.QueryMethods.column;

@Service @RequiredArgsConstructor
public class SentenceFavoriteService {
    private final CurrentUser user;
    private final LearningContext context;
    private final SentenceQuestionMapper questions;
    private final SentenceFavoriteMapper favorites;

    @Transactional
    public Favorite set(long sentenceId, boolean favorite) {
        long userId = user.id();
        context.lockUser(userId);
        var sentence = questions.selectOneById(sentenceId);
        if (sentence == null || (favorite && !"ACTIVE".equals(sentence.getStatus())))
            throw new BusinessException(HttpStatus.NOT_FOUND, "SENTENCE_NOT_FOUND", "句子不存在或不可用");
        var where = QueryWrapper.create().where(column("user_id").eq(userId)).and(column("sentence_id").eq(sentenceId));
        var existing = favorites.selectOneByQuery(where);
        if (favorite && existing == null) {
            var entry = new SentenceFavorite();
            entry.setUserId(userId); entry.setSentenceId(sentenceId); entry.setCreatedAt(context.now());
            if (favorites.insertSelective(entry) != 1) throw new IllegalStateException("Could not save sentence favorite");
        } else if (!favorite && existing != null && favorites.deleteById(existing.getId()) != 1) {
            throw new IllegalStateException("Could not remove sentence favorite");
        }
        return new Favorite(sentenceId, favorite);
    }
}
