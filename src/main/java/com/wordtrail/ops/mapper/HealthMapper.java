package com.wordtrail.ops.mapper;
import org.apache.ibatis.annotations.*;
public interface HealthMapper {
    @Select("""
        SELECT COUNT(*) FROM user_account u
        LEFT JOIN user_auth_session s ON s.user_id=u.id
        LEFT JOIN user_sentence_note n ON n.user_id=u.id
        LEFT JOIN learning_batch b ON b.user_id=u.id AND b.study_mode IS NOT NULL AND b.mode_version>=0
        LEFT JOIN learning_batch_item i ON i.batch_id=b.id AND i.study_attempt_count>=0
        LEFT JOIN word_training_attempt t ON t.batch_item_id=i.id AND t.mode_version>=0
        LEFT JOIN word_review_log l ON l.batch_item_id=i.id AND l.feedback_mode IS NOT NULL
        LEFT JOIN user_learning_setting p ON p.user_id=u.id AND p.preferred_study_mode IS NOT NULL
        WHERE u.id=-1 AND u.auth_version>=0
        """)
    @Options(timeout=2) long ready();
}
