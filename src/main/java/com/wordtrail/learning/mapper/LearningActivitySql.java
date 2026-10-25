package com.wordtrail.learning.mapper;

/** Canonical retained learning activity: a training event and its queue feedback count once. */
public final class LearningActivitySql {
    private LearningActivitySql() {}
    public static final String EVENTS = """
        SELECT i.id AS item_id,l.word_id,l.learning_date,b.task_type,'ANSWER' AS action,0 AS unified
        FROM word_review_log l JOIN learning_batch b ON b.id=l.batch_id AND b.user_id=l.user_id
        JOIN learning_batch_item i ON i.id=l.batch_item_id AND i.batch_id=b.id AND i.word_id=l.word_id
        WHERE l.user_id=#{userId} AND b.practice_mode='RECOGNITION' AND l.event_type='ANSWER'
          AND (b.task_type='PRACTICE' OR b.learning_date=l.learning_date)
          AND (#{from} IS NULL OR l.learning_date>=#{from}) AND l.learning_date<=#{to}
          AND NOT EXISTS(SELECT 1 FROM word_training_attempt t WHERE t.review_log_id=l.id
            AND t.user_id=l.user_id AND t.batch_id=b.id AND t.batch_item_id=i.id AND t.word_id=i.word_id
            AND t.learning_date=l.learning_date)
        UNION ALL
        SELECT i.id AS item_id,t.word_id,t.learning_date,b.task_type,t.action,1 AS unified
        FROM word_training_attempt t JOIN learning_batch b ON b.id=t.batch_id AND b.user_id=t.user_id
        JOIN learning_batch_item i ON i.id=t.batch_item_id AND i.batch_id=b.id AND i.word_id=t.word_id
        WHERE t.user_id=#{userId} AND b.task_type='DAILY' AND b.practice_mode='RECOGNITION'
          AND b.learning_date=t.learning_date
          AND (#{from} IS NULL OR t.learning_date>=#{from}) AND t.learning_date<=#{to}
        """;
}
